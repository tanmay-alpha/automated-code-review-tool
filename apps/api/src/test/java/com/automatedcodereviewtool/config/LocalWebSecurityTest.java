package com.automatedcodereviewtool.config;

import com.automatedcodereviewtool.controller.AuthController;
import com.automatedcodereviewtool.dto.GitHubTokenResponse;
import com.automatedcodereviewtool.dto.GitHubUserInfo;
import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.security.GitHubAuthorizationVerifier;
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
    void corsUsesConfiguredAllowedOriginsList() {
        AppConfig appConfig = new AppConfig();
        appConfig.setFrontendUrl("https://app.example.com/");
        appConfig.setAllowedOrigins(List.of("https://app.example.com", " https://Admin.Example.com:8443 "));
        SecurityConfig securityConfig = new SecurityConfig(null, null, null, appConfig, null, null);

        CorsConfiguration cors = securityConfig.corsConfigurationSource()
                .getCorsConfiguration(new MockHttpServletRequest("GET", "/api/auth/me"));

        assertThat(cors.getAllowedOrigins())
                .containsExactly("https://app.example.com", "https://admin.example.com:8443");
    }

    @Test
    void allowedOriginsDefaultToTheFrontendUrlOrigin() {
        AppConfig appConfig = new AppConfig();
        appConfig.setFrontendUrl("https://app.example.com:443/");

        assertThat(appConfig.getAllowedOrigins()).containsExactly("https://app.example.com");
    }

    @Test
    void allowedOriginsRejectNonOriginValues() {
        AppConfig appConfig = new AppConfig();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> appConfig.setAllowedOrigins(List.of("https://app.example.com/dashboard")))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> appConfig.setAllowedOrigins(List.of("*")))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> appConfig.setAllowedOrigins(List.of("null")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oauthScopesAreConfigurableAndDefaultToPrivateRepoSupport() {
        GitHubConfig defaults = new GitHubConfig();
        defaults.setClientId("id");
        defaults.setOauthRedirectUri("https://app.example.com/api/auth/callback");
        assertThat(new GitHubService(org.springframework.web.reactive.function.client.WebClient.builder(), defaults)
                .getOAuthRedirectUrl()).contains("scope=read%3Auser+repo");

        GitHubConfig publicOnly = new GitHubConfig();
        publicOnly.setClientId("id");
        publicOnly.setOauthRedirectUri("https://app.example.com/api/auth/callback");
        publicOnly.setOauthScopes("public_repo admin:repo_hook");
        String url = new GitHubService(org.springframework.web.reactive.function.client.WebClient.builder(), publicOnly)
                .getOAuthRedirectUrl();
        assertThat(url).contains("scope=public_repo+admin%3Arepo_hook").doesNotContain("scope=read%3Auser+repo");
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
        // Plain HTTP cannot use the __Host- prefix (it requires Secure).
        assertThat(authCookies).anyMatch(cookie -> cookie.startsWith("accessToken="));
        assertThat(authCookies).anyMatch(cookie -> cookie.startsWith("refreshToken="));
        assertThat(authCookies).allMatch(cookie -> !cookie.contains("; Secure"));
        assertThat(authCookies).allMatch(cookie -> cookie.contains("HttpOnly"));
        assertThat(authCookies).allMatch(cookie -> cookie.contains("SameSite=Lax"));
    }

    @Test
    void oauthCookiesRemainSecureInProduction() {
        List<String> cookies = oauthCookies(true);

        assertThat(cookies).hasSize(3);
        assertThat(cookies).allMatch(cookie -> cookie.contains("; Secure"));
        // __Host- prefix: the browser rejects the cookie unless it is Secure,
        // Path=/ and host-only, so sibling subdomains cannot plant it.
        assertThat(cookies).allMatch(cookie -> cookie.startsWith("__Host-"));
        assertThat(cookies).allMatch(cookie -> cookie.contains("Path=/"));
        assertThat(cookies).noneMatch(cookie -> cookie.contains("Domain="));
        assertThat(cookies).allMatch(cookie -> cookie.contains("HttpOnly"));
        assertThat(cookies).allMatch(cookie -> cookie.contains("SameSite=Lax"));
        assertThat(cookies).anyMatch(cookie -> cookie.startsWith("__Host-accessToken="));
        assertThat(cookies).anyMatch(cookie -> cookie.startsWith("__Host-refreshToken="));
        assertThat(cookies).anyMatch(cookie -> cookie.startsWith("__Host-oauth_state=;"));
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
        when(jwtService.generateAccessToken(user.getId(), "alice", 0)).thenReturn("access-jwt");
        when(jwtService.generateRefreshToken(user.getId(), 0)).thenReturn("refresh-jwt");

        // Build a request that carries the oauth_state cookie so CSRF check passes.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/github/callback");
        jakarta.servlet.http.Cookie oauthState = new jakarta.servlet.http.Cookie(
                secure ? "__Host-oauth_state" : "oauth_state", "test-state");
        request.setCookies(oauthState);

        AuthController controller = new AuthController(
                githubService, userService, jwtService, jwtBlacklistService, appConfig, jwtConfig, redis,
                securityEventLogger, mock(GitHubAuthorizationVerifier.class));
        return controller.oauthCallback("oauth-code", "test-state", null, request).getHeaders().get(HttpHeaders.SET_COOKIE);
    }
}