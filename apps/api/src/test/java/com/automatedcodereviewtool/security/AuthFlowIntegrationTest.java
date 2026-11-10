package com.automatedcodereviewtool.security;

import com.automatedcodereviewtool.dto.GitHubTokenResponse;
import com.automatedcodereviewtool.dto.GitHubUserInfo;
import com.automatedcodereviewtool.entity.ApiKey;
import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.logging.SecurityEventLogger;
import com.automatedcodereviewtool.repository.UserRepository;
import com.automatedcodereviewtool.service.GitHubService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OAuth callback failure handling, GitHub-revocation re-auth, "sign out
 * everywhere" and secret-exposure checks, through the real filter chain and
 * H2 database (OWASP ASVS 5.0 V3.3, V3.4, V7.4, V10.4, V16.2; WSTG-ERRH-01,
 * WSTG-SESS-06).
 */
@SpringBootTest(properties = {
        "app.frontend-url=https://app.example.com",
        "app.cookie-secure=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@Import(AuthFlowIntegrationTest.FailingControllerConfig.class)
class AuthFlowIntegrationTest {

    private static final String APP = "https://app.example.com";
    private static final String GITHUB_TOKEN = "gho_SuperSecretGitHubToken123";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private EncryptionService encryptionService;
    @Autowired private UserRepository userRepository;
    @Autowired private ApplicationContext context;

    @MockBean private SecurityEventLogger securityEventLogger;
    @MockBean private StringRedisTemplate redis;
    @MockBean private GitHubService gitHubService;

    private User user;

    @RestController
    static class FailingController {
        @GetMapping("/api/test/explode")
        String explode() {
            throw new IllegalStateException("db password=hunter2 at jdbc:postgresql://secret-host");
        }
    }

    @TestConfiguration
    static class FailingControllerConfig {
        @Bean
        FailingController failingController() {
            return new FailingController();
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        when(redis.hasKey(anyString())).thenReturn(false);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        user = userRepository.save(User.builder()
                .githubId(ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE))
                .githubUsername("alice")
                .accessToken(encryptionService.encrypt(GITHUB_TOKEN))
                .build());
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteById(user.getId());
    }

    private Cookie stateCookie(String value) {
        return new Cookie("__Host-oauth_state", value);
    }

    private Cookie accessCookie(String token) {
        return new Cookie("__Host-accessToken", token);
    }

    private Cookie refreshCookie(String token) {
        return new Cookie("__Host-refreshToken", token);
    }

    // --- OAuth callback failures redirect instead of 4xx/5xx JSON -----------

    @Test
    void consentDeniedRedirectsToFrontendInsteadOf500() throws Exception {
        // GitHub sends ?error=access_denied&state=... and no code when the user cancels.
        mockMvc.perform(get("/api/auth/callback")
                        .param("error", "access_denied")
                        .param("error_description", "The user has denied your application access.")
                        .param("state", "s1")
                        .cookie(stateCookie("s1")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", APP + "/?authError=access_denied"))
                .andExpect(header().stringValues("Set-Cookie", hasItem(startsWith("__Host-oauth_state=;"))))
                .andExpect(header().stringValues("Set-Cookie", not(hasItem(startsWith("__Host-accessToken=")))));

        verify(gitHubService, never()).exchangeCodeForToken(anyString());
    }

    @Test
    void stateMismatchIsRejectedWithoutContactingGitHub() throws Exception {
        mockMvc.perform(get("/api/auth/callback").param("code", "c").param("state", "attacker")
                        .cookie(stateCookie("victim")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", APP + "/?authError=oauth_state_invalid"));
        mockMvc.perform(get("/api/auth/callback").param("code", "c").param("state", "s"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", APP + "/?authError=oauth_state_invalid"));
        // A state cookie without the __Host- prefix may have been tossed by a sibling subdomain.
        mockMvc.perform(get("/api/auth/callback").param("code", "c").param("state", "s")
                        .cookie(new Cookie("oauth_state", "s")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", APP + "/?authError=oauth_state_invalid"));

        verify(gitHubService, never()).exchangeCodeForToken(anyString());
    }

    @Test
    void staleCodeRedirectsWithGithubRejected() throws Exception {
        // GitHub answers 200 {"error":"bad_verification_code"} → no access_token.
        when(gitHubService.exchangeCodeForToken("used-code")).thenReturn(new GitHubTokenResponse(null, null, null));

        mockMvc.perform(get("/api/auth/callback").param("code", "used-code").param("state", "s")
                        .cookie(stateCookie("s")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", APP + "/?authError=github_rejected"));
    }

    @Test
    void githubOutageDuringLoginRedirectsAndDoesNotLogTheToken(CapturedOutput output) throws Exception {
        when(gitHubService.exchangeCodeForToken("code")).thenReturn(new GitHubTokenResponse(GITHUB_TOKEN, "bearer", "repo"));
        when(gitHubService.getCurrentUser(GITHUB_TOKEN))
                .thenThrow(new IllegalStateException("upstream said: bad token " + GITHUB_TOKEN));

        mockMvc.perform(get("/api/auth/callback").param("code", "code").param("state", "s")
                        .cookie(stateCookie("s")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", APP + "/?authError=github_unavailable"));

        assertThat(output.getAll()).doesNotContain(GITHUB_TOKEN);
    }

    @Test
    void sessionStoreOutageDuringLoginRedirectsWithoutCookies() throws Exception {
        ValueOperations<String, String> ops = redis.opsForValue();
        org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"))
                .when(ops).set(anyString(), anyString(), any(Duration.class));
        when(gitHubService.exchangeCodeForToken("code")).thenReturn(new GitHubTokenResponse(GITHUB_TOKEN, "bearer", "repo"));
        when(gitHubService.getCurrentUser(GITHUB_TOKEN)).thenReturn(new GitHubUserInfo(user.getGithubId(), "alice", null));

        mockMvc.perform(get("/api/auth/callback").param("code", "code").param("state", "s")
                        .cookie(stateCookie("s")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", APP + "/?authError=session_unavailable"))
                .andExpect(header().stringValues("Set-Cookie", not(hasItem(startsWith("__Host-accessToken=")))));
    }

    @Test
    void successfulLoginSetsHostPrefixedCookiesAndNeverExposesTheGitHubToken() throws Exception {
        when(gitHubService.exchangeCodeForToken("code")).thenReturn(new GitHubTokenResponse(GITHUB_TOKEN, "bearer", "repo"));
        when(gitHubService.getCurrentUser(GITHUB_TOKEN)).thenReturn(new GitHubUserInfo(user.getGithubId(), "alice", null));

        MvcResult login = mockMvc.perform(get("/api/auth/callback").param("code", "code").param("state", "s")
                        .cookie(stateCookie("s")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", APP + "/dashboard"))
                .andReturn();

        List<String> cookies = login.getResponse().getHeaders("Set-Cookie");
        assertThat(cookies).anyMatch(c -> c.startsWith("__Host-accessToken="))
                .anyMatch(c -> c.startsWith("__Host-refreshToken="))
                .allMatch(c -> c.contains("Secure") && c.contains("HttpOnly") && c.contains("Path=/")
                        && c.contains("SameSite=Lax") && !c.contains("Domain="))
                .noneMatch(c -> c.contains(GITHUB_TOKEN));
        assertThat(login.getResponse().getHeader("Location")).doesNotContain(GITHUB_TOKEN);

        String access = cookies.stream().filter(c -> c.startsWith("__Host-accessToken=")).findFirst().orElseThrow()
                .split(";")[0].substring("__Host-accessToken=".length());
        mockMvc.perform(get("/api/auth/me").cookie(accessCookie(access)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.githubUsername").value("alice"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(content -> assertThat(content.getResponse().getContentAsString())
                        .doesNotContain(GITHUB_TOKEN)
                        .doesNotContain(userRepository.findById(user.getId()).orElseThrow().getAccessToken()));
    }

    // --- GitHub authorization revoked ---------------------------------------

    @Test
    void refreshWithRevokedGitHubGrantRequiresReauthentication() throws Exception {
        when(gitHubService.getCurrentUser(GITHUB_TOKEN)).thenThrow(
                WebClientResponseException.create(401, "Unauthorized", null, null, null));
        String refresh = jwtService.generateRefreshToken(user.getId(), 0);

        mockMvc.perform(post("/api/auth/refresh").cookie(refreshCookie(refresh)).header("Origin", APP))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("GITHUB_REAUTH_REQUIRED"))
                .andExpect(header().stringValues("Set-Cookie", hasItem(startsWith("__Host-accessToken=;"))))
                .andExpect(header().stringValues("Set-Cookie", hasItem(startsWith("__Host-refreshToken=;"))));

        verify(redis).delete("session:refresh:" + user.getId());
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void refreshSurvivesAGitHubOutage() throws Exception {
        when(gitHubService.getCurrentUser(GITHUB_TOKEN)).thenThrow(
                WebClientResponseException.create(503, "Service Unavailable", null, null, null));
        String refresh = jwtService.generateRefreshToken(user.getId(), 0);

        mockMvc.perform(post("/api/auth/refresh").cookie(refreshCookie(refresh)).header("Origin", APP))
                .andExpect(status().isOk())
                .andExpect(header().stringValues("Set-Cookie", hasItem(startsWith("__Host-refreshToken="))));
    }

    @Test
    void refreshWithUndecryptableGitHubTokenRequiresReauthentication() throws Exception {
        user.setAccessToken("not-a-ciphertext");
        userRepository.save(user);
        String refresh = jwtService.generateRefreshToken(user.getId(), 0);

        mockMvc.perform(post("/api/auth/refresh").cookie(refreshCookie(refresh)).header("Origin", APP))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("GITHUB_REAUTH_REQUIRED"));
        verify(gitHubService, never()).getCurrentUser(anyString());
    }

    // --- sign out everywhere ------------------------------------------------

    @Test
    void logoutAllRevokesEveryOutstandingAccessAndRefreshToken() throws Exception {
        when(gitHubService.getCurrentUser(GITHUB_TOKEN)).thenReturn(new GitHubUserInfo(user.getGithubId(), "alice", null));
        String laptopAccess = jwtService.generateAccessToken(user.getId(), "alice", 0);
        String phoneAccess = jwtService.generateAccessToken(user.getId(), "alice", 0);
        String phoneRefresh = jwtService.generateRefreshToken(user.getId(), 0);

        mockMvc.perform(get("/api/auth/me").cookie(accessCookie(phoneAccess))).andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout-all").cookie(accessCookie(laptopAccess)).header("Origin", APP))
                .andExpect(status().isNoContent())
                .andExpect(header().stringValues("Set-Cookie", hasItem(startsWith("__Host-accessToken=;"))));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getTokenVersion()).isEqualTo(1);
        verify(securityEventLogger).logSessionRevocation(eq(user.getId()), eq("logout-all"));
        // The other device's tokens are dead even though nothing was blacklisted for them.
        mockMvc.perform(get("/api/auth/me").cookie(accessCookie(phoneAccess))).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + laptopAccess))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh").cookie(refreshCookie(phoneRefresh)).header("Origin", APP))
                .andExpect(status().isUnauthorized());
        // Tokens minted at the new version work.
        mockMvc.perform(get("/api/auth/me").cookie(accessCookie(jwtService.generateAccessToken(user.getId(), "alice", 1))))
                .andExpect(status().isOk());
    }

    // --- secret exposure ----------------------------------------------------

    @Test
    void unhandledErrorsExposeNoMessageOrStackTrace() throws Exception {
        String access = jwtService.generateAccessToken(user.getId(), "alice", 0);

        mockMvc.perform(get("/api/test/explode").cookie(accessCookie(access)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(content -> assertThat(content.getResponse().getContentAsString())
                        .doesNotContain("hunter2")
                        .doesNotContain("secret-host")
                        .doesNotContain("IllegalStateException")
                        .doesNotContain("at com."));
    }

    @Test
    void invalidTokenErrorsDoNotEchoTheToken() throws Exception {
        String forged = jwtService.generateRefreshToken(user.getId(), 0) + "tampered";

        mockMvc.perform(post("/api/auth/refresh").cookie(refreshCookie(forged)).header("Origin", APP))
                .andExpect(status().isUnauthorized())
                .andExpect(content -> assertThat(content.getResponse().getContentAsString())
                        .doesNotContain(forged)
                        .doesNotContain("tampered"));
    }

    @Test
    void onlyHealthAndInfoActuatorEndpointsAreExposed() throws Exception {
        String access = jwtService.generateAccessToken(user.getId(), "alice", 0);
        for (String endpoint : List.of("env", "configprops", "beans", "heapdump", "loggers", "mappings")) {
            int code = mockMvc.perform(get("/actuator/" + endpoint).cookie(accessCookie(access)))
                    .andReturn().getResponse().getStatus();
            assertThat(code).as("/actuator/" + endpoint).isNotEqualTo(HttpStatus.OK.value());
        }
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(content -> assertThat(content.getResponse().getContentAsString())
                        .doesNotContain("test-ml-secret")
                        .doesNotContain("jdbc:"));
    }

    @Test
    void entityToStringNeverIncludesCredentials() {
        User withToken = User.builder().githubUsername("bob").accessToken("ENCRYPTED-GH-TOKEN").build();
        ApiKey key = ApiKey.builder().user(withToken).label("ci").prefix("cl_live_abcd1234")
                .keyHash("$2a$10$bcrypthashvalue").build();

        assertThat(withToken.toString()).doesNotContain("ENCRYPTED-GH-TOKEN");
        assertThat(key.toString()).doesNotContain("bcrypthashvalue").doesNotContain("ENCRYPTED-GH-TOKEN");
    }

    @Test
    void noGeneratedInMemoryUserOrPasswordExists() {
        assertThat(context.getBeansOfType(InMemoryUserDetailsManager.class)).isEmpty();
        UserDetailsService userDetailsService = context.getBean(UserDetailsService.class);
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("user"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
