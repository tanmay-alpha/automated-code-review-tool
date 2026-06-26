package com.codelens.config;

import com.codelens.controller.AuthController;
import com.codelens.dto.GitHubTokenResponse;
import com.codelens.dto.GitHubUserInfo;
import com.codelens.entity.User;
import com.codelens.security.JwtService;
import com.codelens.service.GitHubService;
import com.codelens.service.UserService;
import org.junit.jupiter.api.Test;
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
        SecurityConfig securityConfig = new SecurityConfig(null, null, null, appConfig);

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

        assertThat(cookies).hasSize(2);
        assertThat(cookies).allMatch(cookie -> !cookie.contains("; Secure"));
        assertThat(cookies).allMatch(cookie -> cookie.contains("HttpOnly"));
        assertThat(cookies).allMatch(cookie -> cookie.contains("SameSite=Lax"));
    }

    @Test
    void oauthCookiesRemainSecureInProduction() {
        assertThat(oauthCookies(true)).allMatch(cookie -> cookie.contains("; Secure"));
    }

    private static List<String> oauthCookies(boolean secure) {
        GitHubService githubService = mock(GitHubService.class);
        UserService userService = mock(UserService.class);
        JwtService jwtService = mock(JwtService.class);

        AppConfig appConfig = new AppConfig();
        appConfig.setCookieSecure(secure);
        JwtConfig jwtConfig = new JwtConfig();
        jwtConfig.setAccessTokenExpiry(900);
        jwtConfig.setRefreshTokenExpiry(604800);

        User user = User.builder()
                .id(UUID.randomUUID())
                .githubId(123L)
                .githubUsername("alice")
                .accessToken("encrypted")
                .build();
        GitHubTokenResponse token = new GitHubTokenResponse("gh-token", "bearer", "repo");
        GitHubUserInfo info = new GitHubUserInfo(123L, "alice", null);

        when(githubService.exchangeCodeForToken("oauth-code")).thenReturn(token);
        when(githubService.getCurrentUser("gh-token")).thenReturn(info);
        when(userService.findOrCreateFromGitHub(info, "gh-token")).thenReturn(user);
        when(jwtService.generateAccessToken(user.getId(), "alice")).thenReturn("access-jwt");
        when(jwtService.generateRefreshToken(user.getId())).thenReturn("refresh-jwt");

        AuthController controller = new AuthController(
                githubService, userService, jwtService, appConfig, jwtConfig);
        return controller.oauthCallback("oauth-code").getHeaders().get(HttpHeaders.SET_COOKIE);
    }
}
