package com.automatedcodereviewtool.controller;

import com.automatedcodereviewtool.config.AppConfig;
import com.automatedcodereviewtool.config.JwtConfig;
import com.automatedcodereviewtool.dto.GitHubTokenResponse;
import com.automatedcodereviewtool.dto.GitHubUserInfo;
import com.automatedcodereviewtool.dto.UserResponse;
import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.logging.SecurityEventLogger;
import com.automatedcodereviewtool.security.AuthCookies;
import com.automatedcodereviewtool.security.GitHubAuthorizationVerifier;
import com.automatedcodereviewtool.security.JwtBlacklistService;
import com.automatedcodereviewtool.security.JwtService;
import com.automatedcodereviewtool.service.GitHubService;
import com.automatedcodereviewtool.service.UserService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GitHub OAuth login plus cookie-based session management.
 *
 * <p>After a successful login the API sets two {@code httpOnly} cookies
 * (access token, 15 min, and refresh token, 7 days; names and attributes in
 * {@link AuthCookies}) and 302-redirects the browser to
 * {@code app.frontend-url}/dashboard. Every OAuth failure (consent denied,
 * bad state, GitHub error, session store down) 302-redirects to
 * {@code app.frontend-url}/?authError=&lt;code&gt; instead of rendering an
 * API error page in the user's browser.</p>
 *
 * <p>Refresh tokens rotate: Redis holds the single valid refresh token per
 * user ({@code session:refresh:<userId>}) and rotation is an atomic
 * compare-and-set; presenting any other token revokes the session. Refresh
 * also requires the token's {@code ver} claim to match the user's token
 * version ({@code POST /logout-all} bumps it) and the stored GitHub grant to
 * still work. Login, refresh and logout fail closed when Redis is
 * unreachable (see {@link JwtBlacklistService}).</p>
 */
@RestController
@RequestMapping("/api/auth")
@EnableConfigurationProperties({AppConfig.class, JwtConfig.class})
public class AuthController {

    private static final String REFRESH_SESSION_KEY_PREFIX = "session:refresh:";

    /** Values of the {@code authError} query parameter on the frontend redirect. */
    static final String AUTH_ERROR_ACCESS_DENIED = "access_denied";
    static final String AUTH_ERROR_STATE = "oauth_state_invalid";
    static final String AUTH_ERROR_GITHUB_UNAVAILABLE = "github_unavailable";
    static final String AUTH_ERROR_GITHUB_REJECTED = "github_rejected";
    static final String AUTH_ERROR_SESSION_UNAVAILABLE = "session_unavailable";

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
    private final AuthCookies authCookies;
    private final GitHubAuthorizationVerifier gitHubAuthorizationVerifier;

    public AuthController(GitHubService githubService,
                          UserService userService,
                          JwtService jwtService,
                          JwtBlacklistService jwtBlacklistService,
                          AppConfig appConfig,
                          JwtConfig jwtConfig,
                          StringRedisTemplate redis,
                          SecurityEventLogger securityEventLogger,
                          GitHubAuthorizationVerifier gitHubAuthorizationVerifier) {
        this.githubService = githubService;
        this.userService = userService;
        this.jwtService = jwtService;
        this.jwtBlacklistService = jwtBlacklistService;
        this.appConfig = appConfig;
        this.jwtConfig = jwtConfig;
        this.redis = redis;
        this.securityEventLogger = securityEventLogger;
        this.authCookies = new AuthCookies(appConfig);
        this.gitHubAuthorizationVerifier = gitHubAuthorizationVerifier;
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
     * the returned state against the cookie to prevent login CSRF.</p>
     */
    @GetMapping("/github")
    public ResponseEntity<Void> startOAuth() {
        String state = generateStateToken();
        String redirectUrl = githubService.getOAuthRedirectUrl() + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);

        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.SET_COOKIE,
                        authCookies.build(AuthCookies.OAUTH_STATE, state, Duration.ofSeconds(300)).toString())
                .location(URI.create(redirectUrl))
                .build();
    }

    /**
     * GitHub redirects the browser here after consent. We validate
     * {@code state} against the cookie set by {@link #startOAuth()}, exchange
     * the code, fetch the user, upsert them, and issue JWT cookies.
     */
    @GetMapping("/callback")
    public ResponseEntity<Void> oauthCallback(@RequestParam(value = "code", required = false) String code,
                                              @RequestParam(value = "state", required = false) String state,
                                              @RequestParam(value = "error", required = false) String error,
                                              HttpServletRequest request) {
        if (error != null) {
            // e.g. the user pressed "Cancel" on GitHub's consent screen.
            return authFailure(AUTH_ERROR_ACCESS_DENIED);
        }
        String cookieState = authCookies.read(request, AuthCookies.OAUTH_STATE);
        if (!stateMatches(state, cookieState)) {
            return authFailure(AUTH_ERROR_STATE);
        }
        if (code == null || code.isBlank()) {
            return authFailure(AUTH_ERROR_GITHUB_REJECTED);
        }

        GitHubTokenResponse token;
        GitHubUserInfo info;
        try {
            token = githubService.exchangeCodeForToken(code);
            if (token == null || token.accessToken() == null) {
                // GitHub answers 200 {"error":"bad_verification_code"} for a stale/replayed code.
                return authFailure(AUTH_ERROR_GITHUB_REJECTED);
            }
            info = githubService.getCurrentUser(token.accessToken());
        } catch (RuntimeException githubFailure) {
            log.warn("GitHub OAuth exchange failed: {}", githubFailure.getClass().getSimpleName());
            return authFailure(AUTH_ERROR_GITHUB_UNAVAILABLE);
        }
        if (info == null || info.id() == null) {
            return authFailure(AUTH_ERROR_GITHUB_REJECTED);
        }

        User user = userService.findOrCreateFromGitHub(info, token.accessToken());
        String access = jwtService.generateAccessToken(user.getId(), user.getGithubUsername(), user.getTokenVersion());
        String refresh = jwtService.generateRefreshToken(user.getId(), user.getTokenVersion());

        try {
            redis.opsForValue().set(REFRESH_SESSION_KEY_PREFIX + user.getId(), refresh, Duration.ofSeconds(jwtConfig.getRefreshTokenExpiry()));
        } catch (DataAccessException e) {
            log.warn("Session store (Redis) unavailable during login: {}", e.getMessage());
            return authFailure(AUTH_ERROR_SESSION_UNAVAILABLE);
        }

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(appConfig.getFrontendUrl() + "/dashboard"))
                .header(HttpHeaders.SET_COOKIE, sessionCookie(AuthCookies.ACCESS_TOKEN, access, jwtConfig.getAccessTokenExpiry()))
                .header(HttpHeaders.SET_COOKIE, sessionCookie(AuthCookies.REFRESH_TOKEN, refresh, jwtConfig.getRefreshTokenExpiry()))
                // The state is single-use: clear it now that the handshake is complete.
                .header(HttpHeaders.SET_COOKIE, authCookies.clear(AuthCookies.OAUTH_STATE).toString())
                .build();
    }

    private static boolean stateMatches(String state, String cookieState) {
        if (state == null || state.isBlank() || cookieState == null) {
            return false;
        }
        return MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),
                cookieState.getBytes(StandardCharsets.UTF_8));
    }

    /** Sends the browser back to the frontend with an error code; never issues session cookies. */
    private ResponseEntity<Void> authFailure(String code) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(appConfig.getFrontendUrl() + "/?authError=" + code))
                .header(HttpHeaders.SET_COOKIE, authCookies.clear(AuthCookies.OAUTH_STATE).toString())
                .build();
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal User user) {
        return UserResponse.from(user);
    }

    /**
     * Trade a valid refresh cookie for a fresh access-token cookie and a rotated refresh-token cookie.
     *
     * <p>Answers 401 {@code GITHUB_REAUTH_REQUIRED} (cookies cleared, refresh
     * session deleted) when GitHub reports the stored grant revoked, so the
     * dashboard sends the user through OAuth again instead of failing later
     * on a GitHub call.</p>
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request) {
        String refreshToken = authCookies.read(request, AuthCookies.REFRESH_TOKEN);
        if (refreshToken == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no refresh token");
        }
        Claims claims;
        try {
            claims = jwtService.validateToken(refreshToken);
        } catch (JwtException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid refresh token");
        }
        if (!JwtService.TYPE_REFRESH.equals(claims.get(JwtService.CLAIM_TYPE))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not a refresh token");
        }
        UUID userId;
        try {
            userId = UUID.fromString(claims.getSubject());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid refresh token");
        }
        User user = userService.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unknown user"));
        if (!JwtService.isCurrentVersion(claims, user.getTokenVersion())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "session revoked");
        }

        if (gitHubAuthorizationVerifier.check(user) == GitHubAuthorizationVerifier.Status.REVOKED) {
            try {
                redis.delete(REFRESH_SESSION_KEY_PREFIX + userId);
            } catch (DataAccessException e) {
                // The refresh token is rejected either way; the session key expires on its own.
                log.warn("Could not delete refresh session after GitHub revocation: {}", e.getMessage());
            }
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, authCookies.clear(AuthCookies.ACCESS_TOKEN).toString())
                    .header(HttpHeaders.SET_COOKIE, authCookies.clear(AuthCookies.REFRESH_TOKEN).toString())
                    .body(Map.of(
                            "error", "GITHUB_REAUTH_REQUIRED",
                            "message", "GitHub authorization was revoked or expired; sign in again",
                            "status", HttpStatus.UNAUTHORIZED.value(),
                            "timestamp", Instant.now().toString()));
        }

        String newAccess = jwtService.generateAccessToken(user.getId(), user.getGithubUsername(), user.getTokenVersion());
        String newRefresh = jwtService.generateRefreshToken(user.getId(), user.getTokenVersion());

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
                .header(HttpHeaders.SET_COOKIE, sessionCookie(AuthCookies.ACCESS_TOKEN, newAccess, jwtConfig.getAccessTokenExpiry()))
                .header(HttpHeaders.SET_COOKIE, sessionCookie(AuthCookies.REFRESH_TOKEN, newRefresh, jwtConfig.getRefreshTokenExpiry()))
                .build();
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
        String jti = currentAccessTokenJti(request);
        try {
            if (jti != null && !jti.isBlank()) {
                jwtBlacklistService.blacklistToken(jti, jwtConfig.getAccessTokenExpiry());
                securityEventLogger.logTokenBlacklisting(jti, userId, "logout");
            }
            redis.delete(REFRESH_SESSION_KEY_PREFIX + userId);
        } catch (DataAccessException e) {
            throw sessionStoreUnavailable(e);
        }
        return clearedSession();
    }

    /**
     * "Sign out everywhere": bumps the user's token version in the database,
     * which invalidates every access and refresh token issued to this user on
     * any device, then clears this browser's cookies. The database is the
     * authority, so this works even when Redis is down; deleting the Redis
     * refresh session is only tidying up.
     */
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal User user) {
        userService.revokeAllSessions(user.getId());
        securityEventLogger.logSessionRevocation(user.getId(), "logout-all");
        try {
            redis.delete(REFRESH_SESSION_KEY_PREFIX + user.getId());
        } catch (DataAccessException e) {
            log.warn("Could not delete refresh session during logout-all: {}", e.getMessage());
        }
        return clearedSession();
    }

    private String currentAccessTokenJti(HttpServletRequest request) {
        String accessToken = authCookies.read(request, AuthCookies.ACCESS_TOKEN);
        if (accessToken == null) {
            String bearerToken = request.getHeader("Authorization");
            if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
                accessToken = bearerToken.substring(7);
            }
        }
        if (accessToken == null) {
            return null;
        }
        try {
            return jwtService.extractJTI(accessToken);
        } catch (JwtException e) {
            // An invalid or expired token can't authenticate again; nothing to revoke.
            log.debug("Logout presented an unusable access token: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private ResponseEntity<Void> clearedSession() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, authCookies.clear(AuthCookies.ACCESS_TOKEN).toString())
                .header(HttpHeaders.SET_COOKIE, authCookies.clear(AuthCookies.REFRESH_TOKEN).toString())
                .build();
    }

    private String sessionCookie(String baseName, String value, long maxAgeSeconds) {
        return authCookies.build(baseName, value, Duration.ofSeconds(maxAgeSeconds)).toString();
    }
}
