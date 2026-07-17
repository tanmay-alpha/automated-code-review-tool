package com.codelens.controller;

import com.codelens.config.AppConfig;
import com.codelens.config.JwtConfig;
import com.codelens.dto.GitHubTokenResponse;
import com.codelens.dto.GitHubUserInfo;
import com.codelens.dto.UserResponse;
import com.codelens.entity.User;
import com.codelens.security.JwtBlacklistService;
import com.codelens.security.JwtService;
import com.codelens.service.GitHubService;
import com.codelens.service.UserService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import com.codelens.logging.SecurityEventLogger;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Implements the GitHub OAuth dance plus session management.
 *
 * <p>After a successful login the API sets two {@code httpOnly} cookies
 * ({@code accessToken} and {@code refreshToken}) and 302-redirects the
 * browser to the configured {@code app.frontend-url}. The browser then
 * authenticates subsequent requests to protected endpoints by sending
 * those cookies back; {@link com.codelens.security.JwtAuthFilter} reads
 * the {@code accessToken} cookie and populates the security context.</p>
 */
@RestController
@RequestMapping("/api/auth")
@EnableConfigurationProperties({AppConfig.class, JwtConfig.class})
public class AuthController {

    static final String ACCESS_TOKEN_COOKIE = "accessToken";
    static final String REFRESH_TOKEN_COOKIE = "refreshToken";

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

    private static final String OAUTH_STATE_COOKIE = "oauth_state";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthController.class);

    private static String generateStateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Start the OAuth flow by 302-ing the browser to GitHub's authorize URL.
     *
     * <p>Generates a random {@code state} token, stores it in an HTTP-only
     * cookie, and appends it to the authorize URL. The callback validates
     * the returned state against the cookie to prevent CSRF.</p>
     */
    @GetMapping("/github")
    public ResponseEntity<Void> startOAuth(HttpServletRequest request) {
        String state = generateStateToken();

        // Store state in an HTTP-only cookie (short-lived, 5 min).
        ResponseCookie stateCookie = ResponseCookie.from(OAUTH_STATE_COOKIE, state)
                .httpOnly(true)
                .secure(appConfig.isCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(300))
                .build();

        String redirectUrl = githubService.getOAuthRedirectUrl() + "&state=" + java.net.URLEncoder.encode(state, java.nio.charset.StandardCharsets.UTF_8);

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
     * during {@link #startOAuth(HttpServletRequest)} to prevent CSRF
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
            redis.opsForValue().set("session:refresh:" + user.getId(), refresh, Duration.ofSeconds(jwtConfig.getRefreshTokenExpiry()));
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(AuthController.class).warn("Failed to save initial refresh token in Redis: {}", e.getMessage());
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
    public UserResponse me(@AuthenticationPrincipal UUID userId) {
        User user = userService.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unknown user"));
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
            
            try {
                String current = redis.opsForValue().get("session:refresh:" + userId);
                if (current == null || !current.equals(refreshToken)) {
                    redis.delete("session:refresh:" + userId);
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "refresh token reused or invalid");
                }
            } catch (ResponseStatusException rse) {
                throw rse;
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(AuthController.class).warn("Redis failure during refresh token check: {}; falling back to stateless", e.getMessage());
            }

            String newAccess = jwtService.generateAccessToken(user.getId(), user.getGithubUsername());
            String newRefresh = jwtService.generateRefreshToken(user.getId());

            try {
                redis.opsForValue().set("session:refresh:" + userId, newRefresh, Duration.ofSeconds(jwtConfig.getRefreshTokenExpiry()));
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(AuthController.class).warn("Failed to rotate refresh token in Redis: {}", e.getMessage());
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
     * Generate a new API key for the authenticated user. The full key is
     * returned ONCE — there is no way to recover it from the database.
     */
    @PostMapping("/api-key/regenerate")
    public Map<String, String> regenerateApiKey(@AuthenticationPrincipal UUID userId) {
        String key = userService.generateApiKey(userId);
        return Map.of("apiKey", key);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        // Get the access token from the current request to blacklist it
        String accessToken = readCookie(request, ACCESS_TOKEN_COOKIE);
        if (accessToken == null && request != null) {
            String bearerToken = request.getHeader("Authorization");
            if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
                accessToken = bearerToken.substring(7);
            }
        }
        if (accessToken != null) {
            try {
                String jti = jwtService.extractJTI(accessToken);
                long expiry = jwtConfig.getAccessTokenExpiry();
                jwtBlacklistService.blacklistToken(jti, expiry);
                if (securityEventLogger != null) {
                    securityEventLogger.logTokenBlacklisting(jti, userId, "logout");
                }
 
                // Also blacklist the refresh token if it exists
                String refreshToken = readCookie(request, REFRESH_TOKEN_COOKIE);
                if (refreshToken != null) {
                    String refreshJti = jwtService.extractJTI(refreshToken);
                    long refreshExpiry = jwtConfig.getRefreshTokenExpiry();
                    jwtBlacklistService.blacklistToken(refreshJti, refreshExpiry);
                }
 
                // Clean up Redis refresh token
                try {
                    redis.delete("session:refresh:" + userId);
                } catch (Exception e) {
                    // Log but don't fail the logout
                    log.error("Failed to clean up refresh token", e);
                }
            } catch (Exception e) {
                // If token extraction fails, still proceed with logout
                log.error("Failed to blacklist token", e);
            }
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
