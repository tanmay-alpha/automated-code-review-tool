package com.automatedcodereviewtool.controller;

import com.automatedcodereviewtool.config.AppConfig;
import com.automatedcodereviewtool.config.JwtConfig;
import com.automatedcodereviewtool.dto.GitHubTokenResponse;
import com.automatedcodereviewtool.dto.GitHubUserInfo;
import com.automatedcodereviewtool.dto.UserResponse;
import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.security.JwtBlacklistService;
import com.automatedcodereviewtool.security.JwtService;
import com.automatedcodereviewtool.service.GitHubService;
import com.automatedcodereviewtool.service.UserService;
import com.automatedcodereviewtool.logging.SecurityEventLogger;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * GitHub OAuth login plus cookie-based session management.
 *
 * <p>After a successful login the API sets two {@code httpOnly} cookies
 * ({@code accessToken}, 15 min, and {@code refreshToken}, 7 days) and
 * 302-redirects the browser to {@code app.frontend-url}.
 * {@link com.automatedcodereviewtool.security.JwtAuthFilter} reads the
 * {@code accessToken} cookie on later requests.</p>
 *
 * <p>Refresh tokens rotate: Redis holds the single valid refresh token per
 * user ({@code session:refresh:<userId>}) and rotation is an atomic
 * compare-and-set; presenting any other token revokes the session. Login,
 * refresh and logout fail closed with 503 when Redis is unreachable (see
 * {@link JwtBlacklistService}).</p>
 */
@RestController
@RequestMapping("/api/auth")
@EnableConfigurationProperties({AppConfig.class, JwtConfig.class})
public class AuthController {

    static final String ACCESS_TOKEN_COOKIE = "accessToken";
    static final String REFRESH_TOKEN_COOKIE = "refreshToken";
    private static final String OAUTH_STATE_COOKIE = "oauth_state";
    private static final String REFRESH_SESSION_KEY_PREFIX = "session:refresh:";

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final GitHubService githubService;
    private final UserService userService;
    private final JwtService jwtService;
    private final JwtBlacklistService jwtBlacklistService;
    private final AppConfig appConfig;
    private final JwtConfig jwtConfig;
    private final StringRedisTemplate redis;
    private final SecurityEventLogger securityEventLogger;

    public AuthController(GitHubService githubService,
                          UserService userService,
                          JwtService jwtService,
                          JwtBlacklistService jwtBlacklistService,
                          AppConfig appConfig,
                          JwtConfig jwtConfig,
                          StringRedisTemplate redis,
                          SecurityEventLogger securityEventLogger) {
        this.githubService = githubService;
        this.userService = userService;
        this.jwtService = jwtService;
        this.jwtBlacklistService = jwtBlacklistService;
        this.appConfig = appConfig;
        this.jwtConfig = jwtConfig;
        this.redis = redis;
        this.securityEventLogger = securityEventLogger;
    }

    private static String generateStateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Start the OAuth flow by 302-ing the browser to GitHub's authorize URL.
     *
     * <p>Generates a random {@code state} token, stores it in an HTTP-only
     * cookie, and appends it to the authorize URL. The callback validates
     * the returned state against the cookie to prevent CSRF.</p>
     */
    @GetMapping("/github")
    public ResponseEntity<Void> startOAuth() {
        String state = generateStateToken();

        // Store state in an HTTP-only cookie (short-lived, 5 min).
        ResponseCookie stateCookie = ResponseCookie.from(OAUTH_STATE_COOKIE, state)
                .httpOnly(true)
                .secure(appConfig.isCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(300))
                .build();

        String redirectUrl = githubService.getOAuthRedirectUrl() + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);

        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.SET_COOKIE, stateCookie.toString())
                .location(URI.create(redirectUrl))
                .build();
    }

    /**
     * GitHub redirects the browser here after consent. We exchange the
     * code, fetch the user, upsert them, and issue JWT cookies.
     *
     * <p>Validates the {@code state} query parameter against the cookie set
     * during {@link #startOAuth()} to prevent CSRF
     * attacks on the OAuth handshake.</p>
     */
    @GetMapping("/callback")
    public ResponseEntity<Void> oauthCallback(@RequestParam("code") String code,
                                              @RequestParam(value = "state", required = false) String state,
                                              HttpServletRequest request) {
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing oauth code");
        }

        // Validate state parameter to prevent CSRF.
        String cookieState = readCookie(request, OAUTH_STATE_COOKIE);
        if (state == null || state.isBlank() || cookieState == null || !state.equals(cookieState)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid oauth state");
        }

        GitHubTokenResponse token = githubService.exchangeCodeForToken(code);
        if (token == null || token.accessToken() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "github token exchange failed");
        }
        GitHubUserInfo info = githubService.getCurrentUser(token.accessToken());
        if (info == null || info.id() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "github /user returned no id");
        }
        User user = userService.findOrCreateFromGitHub(info, token.accessToken());
        String access = jwtService.generateAccessToken(user.getId(), user.getGithubUsername());
        String refresh = jwtService.generateRefreshToken(user.getId());

        try {
            redis.opsForValue().set(REFRESH_SESSION_KEY_PREFIX + user.getId(), refresh, Duration.ofSeconds(jwtConfig.getRefreshTokenExpiry()));
        } catch (DataAccessException e) {
            throw sessionStoreUnavailable(e);
        }

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(appConfig.getFrontendUrl() + "/dashboard"))
                .header(HttpHeaders.SET_COOKIE, buildCookie(ACCESS_TOKEN_COOKIE, access, jwtConfig.getAccessTokenExpiry()).toString())
                .header(HttpHeaders.SET_COOKIE, buildCookie(REFRESH_TOKEN_COOKIE, refresh, jwtConfig.getRefreshTokenExpiry()).toString())
                // Clear the OAuth state cookie now that the handshake is complete.
                .header(HttpHeaders.SET_COOKIE, clearCookie(OAUTH_STATE_COOKIE).toString())
                .build();
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal User user) {
        return UserResponse.from(user);
    }

    /**
     * Trade a valid refresh cookie for a fresh access-token cookie and a rotated refresh-token cookie.
     */
    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh(HttpServletRequest request) {
        String refreshToken = readCookie(request, REFRESH_TOKEN_COOKIE);
        if (refreshToken == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no refresh token");
        }
        try {
            Claims claims = jwtService.validateToken(refreshToken);
            if (!JwtService.TYPE_REFRESH.equals(claims.get(JwtService.CLAIM_TYPE))) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not a refresh token");
            }
            UUID userId = UUID.fromString(claims.getSubject());
            User user = userService.findById(userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unknown user"));

            String newAccess = jwtService.generateAccessToken(user.getId(), user.getGithubUsername());
            String newRefresh = jwtService.generateRefreshToken(user.getId());

            boolean rotated;
            try {
                rotated = rotateRefreshSession(redis, REFRESH_SESSION_KEY_PREFIX + userId, refreshToken,
                        newRefresh, Duration.ofSeconds(jwtConfig.getRefreshTokenExpiry()));
            } catch (DataAccessException e) {
                throw sessionStoreUnavailable(e);
            }
            if (!rotated) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "refresh token reused or invalid");
            }

            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE,
                            buildCookie(ACCESS_TOKEN_COOKIE, newAccess, jwtConfig.getAccessTokenExpiry()).toString())
                    .header(HttpHeaders.SET_COOKIE,
                            buildCookie(REFRESH_TOKEN_COOKIE, newRefresh, jwtConfig.getRefreshTokenExpiry()).toString())
                    .build();
        } catch (JwtException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid refresh token");
        }
    }

    /**
     * Compare-and-set rotation in one Redis round trip: the session moves to
     * the new token only if it still holds the presented one. Any mismatch
     * (reuse of a rotated token, or a concurrent refresh that won) revokes
     * the session, so two requests can never both rotate the same token.
     */
    private static final DefaultRedisScript<Long> ROTATE_REFRESH_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
                return 1
            end
            redis.call('DEL', KEYS[1])
            return 0
            """, Long.class);

    static boolean rotateRefreshSession(StringRedisTemplate redis, String key, String presented,
                                        String next, Duration ttl) {
        Long result = redis.execute(ROTATE_REFRESH_SCRIPT, List.of(key),
                presented, next, String.valueOf(ttl.toSeconds()));
        return Long.valueOf(1L).equals(result);
    }

    private static ResponseStatusException sessionStoreUnavailable(DataAccessException cause) {
        log.warn("Session store (Redis) unavailable: {}", cause.getMessage());
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Session store unavailable; try again shortly");
    }

    /**
     * Blacklists the current access token and deletes the server-side
     * refresh session, so neither cookie can be used again even if a copy
     * survives in the browser. Fails closed: if Redis can't record the
     * revocation the client gets 503 (cookies kept) and can retry, instead of
     * a 204 that leaves both tokens usable.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal User user, HttpServletRequest request) {
        UUID userId = user.getId();
        String accessToken = readCookie(request, ACCESS_TOKEN_COOKIE);
        if (accessToken == null) {
            String bearerToken = request.getHeader("Authorization");
            if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
                accessToken = bearerToken.substring(7);
            }
        }
        String jti = null;
        if (accessToken != null) {
            try {
                jti = jwtService.extractJTI(accessToken);
            } catch (JwtException e) {
                // An invalid or expired token can't authenticate again; nothing to revoke.
                log.debug("Logout presented an unusable access token: {}", e.getMessage());
            }
        }
        try {
            if (jti != null && !jti.isBlank()) {
                jwtBlacklistService.blacklistToken(jti, jwtConfig.getAccessTokenExpiry());
                securityEventLogger.logTokenBlacklisting(jti, userId, "logout");
            }
            redis.delete(REFRESH_SESSION_KEY_PREFIX + userId);
        } catch (DataAccessException e) {
            throw sessionStoreUnavailable(e);
        }

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearCookie(ACCESS_TOKEN_COOKIE).toString())
                .header(HttpHeaders.SET_COOKIE, clearCookie(REFRESH_TOKEN_COOKIE).toString())
                .build();
    }

    private ResponseCookie buildCookie(String name, String value, long maxAgeSeconds) {
        // Secure is configurable so local HTTP development can authenticate,
        // while production keeps cookies restricted to HTTPS.
        // httpOnly=true so JS can't read it (XSS-resistant). SameSite=Lax so
        // top-level GET navigations (the OAuth callback) still work.
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(appConfig.isCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .build();
    }

    private ResponseCookie clearCookie(String name) {
        return ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(appConfig.isCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }

    private static String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
