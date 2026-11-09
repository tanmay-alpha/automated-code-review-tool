package com.automatedcodereviewtool.security;

import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.logging.SecurityEventLogger;
import com.automatedcodereviewtool.repository.UserRepository;
import com.automatedcodereviewtool.service.ApiKeyService;
import com.automatedcodereviewtool.service.RepoService;
import com.automatedcodereviewtool.webhook.HmacVerifier;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CSRF / origin verification through the real filter chain
 * (OWASP ASVS 5.0 V3.5.1-V3.5.3, WSTG-SESS-05).
 *
 * <p>The deployment under test serves the dashboard from
 * {@code https://app.example.com}. Each negative case models what a browser
 * sends when a page on another origin submits a request that would carry
 * the victim's cookies. Every rejection is asserted on the
 * {@code CSRF_ORIGIN_REJECTED} body, which only {@link OriginVerificationFilter}
 * produces: Spring's CORS processor rejects some of the same requests with a
 * plain-text 403, so status alone would not prove the filter is in place.</p>
 */
@SpringBootTest(properties = {
        "app.frontend-url=https://app.example.com",
        "app.allowed-origins=https://app.example.com",
        "app.cookie-secure=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CsrfOriginIntegrationTest {

    private static final String APP = "https://app.example.com";
    private static final String CROSS_SITE = "https://attacker.example.net";
    /** Same site (example.com) but a different origin: SameSite=Lax cookies ARE attached. */
    private static final String SIBLING_SUBDOMAIN = "https://blog.example.com";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private CorsConfigurationSource corsConfigurationSource;

    @MockBean private SecurityEventLogger securityEventLogger;
    @MockBean private StringRedisTemplate redis;
    @MockBean private HmacVerifier hmacVerifier;
    @MockBean private UserRepository userRepository;
    @MockBean private RepoService repoService;
    @MockBean private ApiKeyService apiKeyService;
    @MockBean private GitHubAuthorizationVerifier gitHubAuthorizationVerifier;

    private UUID userId;
    private String accessToken;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userId = UUID.randomUUID();
        accessToken = jwtService.generateAccessToken(userId, "victim", 0);
        when(userRepository.findById(any())).thenAnswer(inv -> Optional.of(User.builder()
                .id(inv.getArgument(0))
                .githubUsername("victim")
                .accessToken("encrypted")
                .build()));
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        when(redis.hasKey(anyString())).thenReturn(false);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        when(hmacVerifier.verify(any(), any(), any())).thenReturn(false);
        when(gitHubAuthorizationVerifier.check(any())).thenReturn(GitHubAuthorizationVerifier.Status.VALID);
    }

    private Cookie sessionCookie() {
        return new Cookie("__Host-accessToken", accessToken);
    }

    private Cookie refreshCookie() {
        return new Cookie("__Host-refreshToken", jwtService.generateRefreshToken(userId, 0));
    }

    private ResultActions expectRejected(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(OriginVerificationFilter.ERROR_CODE))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    // --- negative: forged cookie-authenticated requests ----------------------

    @Test
    void crossSiteOriginIsRejected() throws Exception {
        expectRejected(post("/api/auth/logout").cookie(sessionCookie()).header("Origin", CROSS_SITE));

        verify(redis, never()).delete(anyString());
        verify(securityEventLogger).logSecurityViolation(
                org.mockito.ArgumentMatchers.eq(OriginVerificationFilter.ERROR_CODE), anyString(), anyString());
    }

    @Test
    void opaqueNullOriginIsRejected() throws Exception {
        // Sandboxed iframes, data: URLs and some redirects send "Origin: null".
        expectRejected(post("/api/auth/logout").cookie(sessionCookie()).header("Origin", "null"));
    }

    @Test
    void missingOriginWithCrossSiteFetchMetadataIsRejected() throws Exception {
        // Without an Origin header Spring's CORS processor does not treat the
        // request as cross-origin at all; only the origin filter stops it.
        expectRejected(post("/api/auth/logout").cookie(sessionCookie()).header("Sec-Fetch-Site", "cross-site"));
    }

    @Test
    void missingOriginWithSameSiteFetchMetadataIsRejected() throws Exception {
        // same-site != same-origin: a sibling subdomain must not pass.
        expectRejected(post("/api/auth/logout").cookie(sessionCookie()).header("Sec-Fetch-Site", "same-site"));
    }

    @Test
    void siblingSubdomainOriginIsRejected() throws Exception {
        // The browser attaches SameSite=Lax cookies here (same registrable domain).
        expectRejected(post("/api/auth/logout").cookie(sessionCookie()).header("Origin", SIBLING_SUBDOMAIN));
    }

    @Test
    void schemeMismatchIsRejected() throws Exception {
        expectRejected(post("/api/auth/logout").cookie(sessionCookie()).header("Origin", "http://app.example.com"));
    }

    @Test
    void portMismatchIsRejected() throws Exception {
        expectRejected(post("/api/auth/logout").cookie(sessionCookie()).header("Origin", APP + ":8443"));
    }

    @Test
    void suffixLookalikeOriginIsRejected() throws Exception {
        expectRejected(post("/api/auth/logout").cookie(sessionCookie()).header("Origin", APP + ".attacker.net"));
    }

    @Test
    void missingOriginWithForeignRefererIsRejected() throws Exception {
        expectRejected(post("/api/auth/logout").cookie(sessionCookie()).header("Referer", CROSS_SITE + "/x"));
    }

    @Test
    void requestWithoutAnyProvenanceHeaderIsRejected() throws Exception {
        expectRejected(post("/api/auth/logout").cookie(sessionCookie()));
    }

    @Test
    void bodylessRefreshFromSiblingIsRejectedBeforeRotation() throws Exception {
        // CORS-simple request (POST, no body) that a sibling page could send
        // with fetch(..., {credentials: "include", mode: "no-cors"}).
        expectRejected(post("/api/auth/refresh").cookie(refreshCookie()).header("Origin", SIBLING_SUBDOMAIN));

        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void bodylessLogoutAllFromSiblingIsRejected() throws Exception {
        expectRejected(post("/api/auth/logout-all").cookie(sessionCookie()).header("Origin", SIBLING_SUBDOMAIN));

        verify(userRepository, never()).incrementTokenVersion(any());
    }

    @Test
    void crossSiteTextPlainPostToJsonEndpointIsRejected() throws Exception {
        // An HTML form can send text/plain without a preflight.
        expectRejected(post("/api/repos/connect").cookie(sessionCookie())
                .header("Origin", CROSS_SITE)
                .contentType(MediaType.TEXT_PLAIN)
                .content("{\"fullName\":\"victim/repo\"}"));

        verify(repoService, never()).connect(any(), anyString());
    }

    @Test
    void crossSiteFormPostIsRejected() throws Exception {
        expectRejected(post("/api/scan/action").cookie(sessionCookie())
                .header("Origin", CROSS_SITE)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("findingId=" + UUID.randomUUID() + "&action=dismiss"));
    }

    @Test
    void crossSiteDeleteIsRejected() throws Exception {
        UUID keyId = UUID.randomUUID();
        expectRejected(delete("/api/auth/api-keys/" + keyId).cookie(sessionCookie()).header("Origin", CROSS_SITE));

        verify(apiKeyService, never()).revokeKey(any(), any());
    }

    // --- positive: legitimate traffic still flows ----------------------------

    @Test
    void allowedOriginPasses() throws Exception {
        mockMvc.perform(post("/api/auth/logout").cookie(sessionCookie()).header("Origin", APP))
                .andExpect(status().isNoContent());
    }

    @Test
    void sameOriginFetchMetadataWithoutOriginPasses() throws Exception {
        mockMvc.perform(post("/api/auth/logout").cookie(sessionCookie()).header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isNoContent());
    }

    @Test
    void allowedRefererWithoutOriginOrFetchMetadataPasses() throws Exception {
        mockMvc.perform(post("/api/auth/logout").cookie(sessionCookie()).header("Referer", APP + "/settings"))
                .andExpect(status().isNoContent());
    }

    @Test
    void refreshFromAllowedOriginRotates() throws Exception {
        mockMvc.perform(post("/api/auth/refresh").cookie(refreshCookie()).header("Origin", APP))
                .andExpect(status().isOk());
    }

    @Test
    void bearerAuthenticatedRequestsAreExempt() throws Exception {
        // VS Code extension / GitHub Action: no ambient credential to abuse.
        // (Non-browser clients send no Origin; a browser could only attach
        // Authorization cross-origin after a CORS preflight the API refuses.)
        mockMvc.perform(post("/api/auth/logout")
                        .cookie(sessionCookie())
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isNoContent());
    }

    @Test
    void webhookIsExemptEvenWhenACookieIsPresent() throws Exception {
        mockMvc.perform(post("/api/webhook/github").cookie(sessionCookie())
                        .header("Sec-Fetch-Site", "cross-site")
                        .header("X-GitHub-Delivery", "d-1")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-Hub-Signature-256", "sha256=bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"opened\",\"repository\":{\"id\":1}}"))
                // Reaches HMAC verification (400), not the origin check (403).
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsafeRequestWithoutSessionCookieIsNotAnOriginMatter() throws Exception {
        mockMvc.perform(post("/api/auth/logout").header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cookieWithoutHostPrefixIsNotASessionInSecureMode() throws Exception {
        // A cookie tossed by a sibling subdomain cannot carry the __Host- prefix.
        mockMvc.perform(get("/api/auth/me").cookie(new Cookie("accessToken", accessToken)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").cookie(sessionCookie()))
                .andExpect(status().isOk());
    }

    @Test
    void safeMethodsAreNotOriginChecked() throws Exception {
        mockMvc.perform(get("/api/auth/me").cookie(sessionCookie()).header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isOk());
    }

    // --- CORS preflight vs simple requests -----------------------------------

    @Test
    void jsonRequestsNeedAPreflightThatForeignOriginsFail() throws Exception {
        mockMvc.perform(options("/api/repos/connect")
                        .header("Origin", SIBLING_SUBDOMAIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));

        mockMvc.perform(options("/api/repos/connect")
                        .header("Origin", APP)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", APP))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void jsonEndpointsIgnoreCorsSimpleContentTypesEvenFromAnAllowedOrigin() throws Exception {
        // Why only bodyless POSTs were CORS-simple CSRF candidates: JSON
        // endpoints refuse text/plain and form bodies before any handler runs.
        mockMvc.perform(post("/api/repos/connect").cookie(sessionCookie())
                        .header("Origin", APP)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{\"fullName\":\"victim/repo\"}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(201));
        verify(repoService, never()).connect(any(), anyString());
    }

    @Test
    void corsAloneDoesNotStopRequestsThatOmitOrigin() throws Exception {
        // Documents why the origin filter exists: Spring's CORS processor only
        // acts on requests that carry an Origin header.
        CorsFilter cors = new CorsFilter(corsConfigurationSource);

        MockHttpServletRequest noOrigin = new MockHttpServletRequest(HttpMethod.POST.name(), "/api/auth/logout");
        noOrigin.addHeader("Sec-Fetch-Site", "cross-site");
        MockFilterChain passed = new MockFilterChain();
        cors.doFilter(noOrigin, new MockHttpServletResponse(), passed);
        assertThat(passed.getRequest()).as("CORS lets an Origin-less request through").isNotNull();

        MockHttpServletRequest foreign = new MockHttpServletRequest(HttpMethod.POST.name(), "/api/auth/logout");
        foreign.addHeader("Origin", CROSS_SITE);
        MockFilterChain blocked = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        cors.doFilter(foreign, response, blocked);
        assertThat(blocked.getRequest()).as("CORS rejects a disallowed Origin").isNull();
        assertThat(response.getStatus()).isEqualTo(403);
    }
}
