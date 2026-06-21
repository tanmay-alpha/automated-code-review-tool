package com.codelens.controller;

import com.codelens.config.AppConfig;
import com.codelens.config.JwtConfig;
import com.codelens.dto.GitHubTokenResponse;
import com.codelens.dto.GitHubUserInfo;
import com.codelens.dto.UserResponse;
import com.codelens.entity.User;
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

import java.net.URI;
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
    private final AppConfig appConfig;
    private final JwtConfig jwtConfig;

    public AuthController(GitHubService githubService,
                          UserService userService,
                          JwtService jwtService,
                          AppConfig appConfig,
                          JwtConfig jwtConfig) {
        this.githubService = githubService;
        this.userService = userService;
        this.jwtService = jwtService;
        this.appConfig = appConfig;
        this.jwtConfig = jwtConfig;
    }

    /**
     * Start the OAuth flow by 302-ing the browser to GitHub's authorize URL.
     */
    @GetMapping("/github")
    public ResponseEntity<Void> startOAuth() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(githubService.getOAuthRedirectUrl()))
                .build();
    }

    /**
     * GitHub redirects the browser here after consent. We exchange the
     * code, fetch the user, upsert them, and issue JWT cookies.
     */
    @GetMapping("/callback")
    public ResponseEntity<Void> oauthCallback(@RequestParam("code") String code) {
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing oauth code");
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
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(appConfig.getFrontendUrl() + "/dashboard"))
                .header(HttpHeaders.SET_COOKIE, buildCookie(ACCESS_TOKEN_COOKIE, access, jwtConfig.getAccessTokenExpiry()).toString())
                .header(HttpHeaders.SET_COOKIE, buildCookie(REFRESH_TOKEN_COOKIE, refresh, jwtConfig.getRefreshTokenExpiry()).toString())
                .build();
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal UUID userId) {
        User user = userService.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unknown user"));
        return UserResponse.from(user);
    }

    /**
     * Trade a valid refresh cookie for a fresh access-token cookie.
     * We do not rotate the refresh token here — keep MVP simple.
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
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE,
                            buildCookie(ACCESS_TOKEN_COOKIE, newAccess, jwtConfig.getAccessTokenExpiry()).toString())
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
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearCookie(ACCESS_TOKEN_COOKIE).toString())
                .header(HttpHeaders.SET_COOKIE, clearCookie(REFRESH_TOKEN_COOKIE).toString())
                .build();
    }

    // --- helpers -----------------------------------------------------------

    private static ResponseCookie buildCookie(String name, String value, long maxAgeSeconds) {
        // Secure=true so the browser only sends the cookie over HTTPS in prod.
        // httpOnly=true so JS can't read it (XSS-resistant). SameSite=Lax so
        // top-level GET navigations (the OAuth callback) still work.
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .build();
    }

    private static ResponseCookie clearCookie(String name) {
        return ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(true)
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
