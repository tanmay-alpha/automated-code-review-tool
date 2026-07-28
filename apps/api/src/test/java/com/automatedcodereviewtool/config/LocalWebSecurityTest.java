package com.automatedcodereviewtool.config;

import com.automatedcodereviewtool.controller.AuthController;
import com.automatedcodereviewtool.dto.GitHubTokenResponse;
import com.automatedcodereviewtool.dto.GitHubUserInfo;
import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.security.JwtService;
import com.automatedcodereviewtool.security.JwtBlacklistService;
import com.automatedcodereviewtool.logging.SecurityEventLogger;
import com.automatedcodereviewtool.service.GitHubService;
import com.automatedcodereviewtool.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalWebSecurityTest {

    @Test
    void corsAllowsConfiguredFrontendWithCredentials() {
        AppConfig appConfig = new AppConfig();
        appConfig.setFrontendUrl("http://localhost:3000");
        SecurityConfig securityConfig = new SecurityConfig(null, null, null, appConfig, null, null);

        CorsConfiguration cors = securityConfig.corsConfigurationSource()
                .getCorsConfiguration(new MockHttpServletRequest("GET", "/api/auth/me"));

        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedOrigins()).containsExactly("http://localhost:3000");
        assertThat(cors.getAllowCredentials()).isTrue();
        assertThat(cors.getAllowedMethods()).contains("GET", "POST", "DELETE", "OPTIONS");
    }

    @Test
    void oauthCookiesAreUsableOnLocalHttpWhenSecureIsDisabled() {
        List<String> cookies = oauthCookies(false);

        // The controller also clears the oauth_state cookie (security hygiene);
        // filter it out so the assertion targets the auth cookies only.
        List<String> authCookies = cookies.stream()
                .filter(c -> !c.startsWith("oauth_state"))
                .toList();

        assertThat(authCookies).hasSize(2);
        assertThat(authCookies).allMatch(cookie -> !cookie.contains("; Secure"));
        assertThat(authCookies).allMatch(cookie -> cookie.contains("HttpOnly"));
        assertThat(authCookies).allMatch(cookie -> cookie.contains("SameSite=Lax"));
    }

    @Test
    void oauthCookiesRemainSecureInProduction() {
        assertThat(oauthCookies(true).stream()
                .filter(c -> !c.startsWith("oauth_state"))
                .toList()).allMatch(cookie -> cookie.contains("; Secure"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> oauthCookies(boolean secure) {
        GitHubService githubService = mock(GitHubService.class);
        UserService userService = mock(UserService.class);
        JwtService jwtService = mock(JwtService.class);
        JwtBlacklistService jwtBlacklistService = mock(JwtBlacklistService.class);
        SecurityEventLogger securityEventLogger = mock(SecurityEventLogger.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);

        AppConfig appConfig = new AppConfig();
        appConfig.setCookieSecure(secure);
        JwtConfig jwtConfig = new JwtConfig();
        jwtConfig.setAccessTokenExpiry(900);
        jwtConfig.setRefreshTokenExpiry(604800L);

        User user = User.builder()
                .id(UUID.randomUUID())
                .githubId(123L)
                .githubUsername("alice")
                .accessToken("encrypted")
                .build();
        GitHubTokenResponse token = new GitHubTokenResponse("gh-token", "bearer", "repo");
        GitHubUserInfo info = new GitHubUserInfo(123L, "alice", null);

        when(redis.opsForValue()).thenReturn(valueOps);
        when(githubService.exchangeCodeForToken("oauth-code")).thenReturn(token);
        when(githubService.getCurrentUser("gh-token")).thenReturn(info);
        when(userService.findOrCreateFromGitHub(info, "gh-token")).thenReturn(user);
        when(jwtService.generateAccessToken(user.getId(), "alice")).thenReturn("access-jwt");
        when(jwtService.generateRefreshToken(user.getId())).thenReturn("refresh-jwt");

        // Build a request that carries the oauth_state cookie so CSRF check passes.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/github/callback");
        jakarta.servlet.http.Cookie oauthState = new jakarta.servlet.http.Cookie("oauth_state", "test-state");
        request.setCookies(oauthState);

        AuthController controller = new AuthController(
                githubService, userService, jwtService, jwtBlacklistService, appConfig, jwtConfig, redis, securityEventLogger);
        return controller.oauthCallback("oauth-code", "test-state", request).getHeaders().get(HttpHeaders.SET_COOKIE);
    }
}