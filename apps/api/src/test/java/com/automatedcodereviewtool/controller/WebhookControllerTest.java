package com.automatedcodereviewtool.controller;

import com.automatedcodereviewtool.config.SecurityConfig;
import com.automatedcodereviewtool.security.ApiKeyAuthFilter;
import com.automatedcodereviewtool.security.AuthRateLimitFilter;
import com.automatedcodereviewtool.security.JwtAuthFilter;
import com.automatedcodereviewtool.security.JwtService;
import com.automatedcodereviewtool.service.ApiKeyService;
import com.automatedcodereviewtool.service.WebhookService;
import com.automatedcodereviewtool.logging.SecurityEventLogger;
import com.automatedcodereviewtool.webhook.GitHubWebhookEvent;
import com.automatedcodereviewtool.webhook.HmacVerificationException;
import com.automatedcodereviewtool.webhook.HmacVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice tests for {@link WebhookController}.
 *
 * <p>Uses {@code @WebMvcTest} so only the web layer is loaded. The
 * HmacVerifier and WebhookService are
 * mocked. SecurityConfig is imported so requests go through the
 * production filter chain (with the webhook path on permitAll); the
 * JwtService / JwtAuthFilter it depends on are stubbed to a pass-through
 * filter to avoid pulling in EncryptionService.</p>
 */
@WebMvcTest(controllers = WebhookController.class)
@Import(SecurityConfig.class)
class WebhookControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WebhookController webhookController;
    @Autowired com.automatedcodereviewtool.security.RequestSizeLimitFilter requestSizeLimitFilter;

    @MockBean HmacVerifier hmacVerifier;
    @MockBean WebhookService webhookService;
    // SecurityConfig wires JwtAuthFilter which needs JwtService; stub both
    // with a pass-through filter so the chain actually calls into the
    // controller (a Mockito mock filter would do nothing and stall the
    // request).
    @MockBean JwtService jwtService;
    // SecurityConfig also now requires ApiKeyAuthFilter — mock it so the
    // MVC slice doesn't try to load Redis / ApiKeyRepository.
    @MockBean ApiKeyAuthFilter apiKeyAuthFilter;
    @MockBean ApiKeyService apiKeyService;
    // SecurityConfig also wires AuthRateLimitFilter (needs Redis) — mock
    // it as a pass-through alongside ApiKeyAuthFilter.
    @MockBean AuthRateLimitFilter authRateLimitFilter;
    @MockBean SecurityEventLogger securityEventLogger;

    @org.junit.jupiter.api.BeforeEach
    void stubApiKeyFilter() throws Exception {
        // The mock would otherwise no-op and stall the chain.
        doAnswer(inv -> {
            jakarta.servlet.ServletRequest req = inv.getArgument(0);
            jakarta.servlet.ServletResponse res = inv.getArgument(1);
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(req, res);
            return null;
        }).when(apiKeyAuthFilter).doFilter(
                org.mockito.ArgumentMatchers.any(jakarta.servlet.ServletRequest.class),
                org.mockito.ArgumentMatchers.any(jakarta.servlet.ServletResponse.class),
                org.mockito.ArgumentMatchers.any(FilterChain.class));
        doAnswer(inv -> {
            jakarta.servlet.ServletRequest req = inv.getArgument(0);
            jakarta.servlet.ServletResponse res = inv.getArgument(1);
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(req, res);
            return null;
        }).when(authRateLimitFilter).doFilter(
                org.mockito.ArgumentMatchers.any(jakarta.servlet.ServletRequest.class),
                org.mockito.ArgumentMatchers.any(jakarta.servlet.ServletResponse.class),
                org.mockito.ArgumentMatchers.any(FilterChain.class));
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class StubFilterConfig {
        @org.springframework.context.annotation.Bean
        JwtAuthFilter jwtAuthFilter() {
            return new JwtAuthFilter(null, null, null) {
                @Override
                protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                                FilterChain chain) throws java.io.IOException, jakarta.servlet.ServletException {
                    chain.doFilter(req, res);
                }
            };
        }
    }

    private static final String DELIVERY = "01234567-89ab-cdef-0123-456789abcdef";

    private String payload(String action, long repoId) throws Exception {
        GitHubWebhookEvent ev = new GitHubWebhookEvent(
                action,
                new GitHubWebhookEvent.PrPayload(
                        42, "Add feature",
                        new GitHubWebhookEvent.UserPayload("alice"),
                        new GitHubWebhookEvent.HeadPayload("abc123sha"),
                        "https://api.github.com/.../diff",
                        "https://github.com/o/r/pull/42"),
                new GitHubWebhookEvent.RepoPayload(repoId, "o/r"));
        return objectMapper.writeValueAsString(ev);
    }

    @Test
    void testValidWebhookReturns200() throws Exception {
        String body = payload("opened", 123L);
        when(hmacVerifier.verify(aryEq(utf8(body)), anyString(), eq("123"))).thenReturn(true);
        when(webhookService.receiveDelivery(eq(DELIVERY), any(GitHubWebhookEvent.class), eq(123L)))
                .thenReturn(true);

        mockMvc.perform(post("/api/webhook/github")
                        .header("X-Hub-Signature-256", "sha256=valid")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", DELIVERY)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        verify(webhookService, times(1)).processAsync(DELIVERY);
    }

    @Test
    void testForgedSignatureReturns400() throws Exception {
        String body = payload("opened", 123L);
        when(hmacVerifier.verify(any(byte[].class), anyString(), anyString())).thenReturn(false);

        mockMvc.perform(post("/api/webhook/github")
                        .header("X-Hub-Signature-256", "sha256=forged")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", DELIVERY)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(webhookService, never()).processAsync(anyString());
    }

    @Test
    void testDuplicateDeliveryIdReturns200WithNoProcessing() throws Exception {
        String body = payload("opened", 123L);
        when(hmacVerifier.verify(aryEq(utf8(body)), anyString(), eq("123"))).thenReturn(true);
        when(webhookService.receiveDelivery(eq(DELIVERY), any(GitHubWebhookEvent.class), eq(123L)))
                .thenReturn(false);

        mockMvc.perform(post("/api/webhook/github")
                        .header("X-Hub-Signature-256", "sha256=valid")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", DELIVERY)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        verify(webhookService, never()).processAsync(anyString());
    }

    @Test
    void testDurableIngressFailureReturns503ForRedelivery() throws Exception {
        String body = payload("opened", 123L);
        when(hmacVerifier.verify(aryEq(utf8(body)), anyString(), eq("123"))).thenReturn(true);
        when(webhookService.receiveDelivery(eq(DELIVERY), any(GitHubWebhookEvent.class), eq(123L)))
                .thenThrow(new IllegalStateException("database unavailable"));

        mockMvc.perform(post("/api/webhook/github")
                        .header("X-Hub-Signature-256", "sha256=valid")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", DELIVERY)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isServiceUnavailable());

        verify(webhookService, never()).processAsync(anyString());
    }

    @Test
    void testPingEventReturns200Immediately() throws Exception {
        // Ping payload has no pull_request field — controller returns 200
        // without ever calling the verifier or processor.
        String ping = "{\"zen\":\"Anything added dilutes everything else.\"}";

        mockMvc.perform(post("/api/webhook/github")
                        .header("X-GitHub-Event", "ping")
                        .header("X-GitHub-Delivery", DELIVERY)
                        .contentType("application/json")
                        .content(ping))
                .andExpect(status().isOk());

        verify(hmacVerifier, never()).verify(any(byte[].class), anyString(), anyString());
        verify(webhookService, never()).processAsync(anyString());
    }

    @Test
    void testUnknownRepoReturns200() throws Exception {
        // HmacVerifier throws when we have no secret for the repo — that means
        // the webhook isn't for one of our installed repos, so 200 + ignore.
        String body = payload("opened", 123L);
        when(hmacVerifier.verify(aryEq(utf8(body)), anyString(), eq("123")))
                .thenThrow(new HmacVerificationException("no repo"));

        mockMvc.perform(post("/api/webhook/github")
                        .header("X-Hub-Signature-256", "sha256=valid")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", DELIVERY)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        verify(webhookService, never()).processAsync(anyString());
    }

    @Test
    void signatureIsCheckedOverTheExactRawBytes() throws Exception {
        // UTF-8 bytes declared as ISO-8859-1: any decode/re-encode (e.g. a
        // String @RequestBody) would turn each multi-byte character into
        // different bytes, so only the raw request bytes match aryEq(raw).
        byte[] raw = ("{\"action\":\"opened\",\"repository\":{\"id\":123},"
                + "\"pull_request\":{\"number\":42,\"title\":\"Café ✓ \\u00e9\","
                + "\"head\":{\"sha\":\"abc123sha\"}}}").getBytes(StandardCharsets.UTF_8);
        when(hmacVerifier.verify(aryEq(raw), anyString(), eq("123"))).thenReturn(true);
        when(webhookService.receiveDelivery(eq(DELIVERY), any(GitHubWebhookEvent.class), eq(123L)))
                .thenReturn(true);

        mockMvc.perform(post("/api/webhook/github")
                        .header("X-Hub-Signature-256", "sha256=valid")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", DELIVERY)
                        .contentType("application/json;charset=ISO-8859-1")
                        .content(raw))
                .andExpect(status().isOk());

        verify(hmacVerifier).verify(aryEq(raw), anyString(), eq("123"));
        verify(webhookService).processAsync(DELIVERY);
    }

    @Test
    void sizeFilterBoundsAChunkedBodyWhileItIsRead() throws Exception {
        // No Content-Length (chunked): the size filter can't reject it up
        // front, so the stream it hands downstream must stop the read.
        byte[] oversized = new byte[1_048_576 + 10];
        java.util.Arrays.fill(oversized, (byte) ' ');
        java.util.concurrent.atomic.AtomicBoolean reached = new java.util.concurrent.atomic.AtomicBoolean();

        requestSizeLimitFilter.doFilter(chunked(oversized),
                new org.springframework.mock.web.MockHttpServletResponse(), (req, res) -> {
                    reached.set(true);
                    org.assertj.core.api.Assertions.assertThatThrownBy(() -> req.getInputStream().readAllBytes())
                            .isInstanceOf(com.automatedcodereviewtool.security.RequestSizeLimitFilter.BodyTooLargeException.class);
                });

        org.assertj.core.api.Assertions.assertThat(reached).isTrue();
    }

    @Test
    void chunkedBodyOverTheLimitIsRejectedByTheControllerItself() {
        byte[] oversized = new byte[1_048_576 + 1];
        java.util.Arrays.fill(oversized, (byte) ' ');

        org.springframework.http.ResponseEntity<Void> result = webhookController.handle(
                chunked(oversized), "sha256=x", "pull_request", DELIVERY);

        org.assertj.core.api.Assertions.assertThat(result.getStatusCode().value()).isEqualTo(413);
        verify(hmacVerifier, never()).verify(any(byte[].class), anyString(), anyString());
    }

    /** A request whose body is readable but whose length is undeclared. */
    private static org.springframework.mock.web.MockHttpServletRequest chunked(byte[] body) {
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/webhook/github") {
                    @Override
                    public int getContentLength() {
                        return -1;
                    }

                    @Override
                    public long getContentLengthLong() {
                        return -1L;
                    }
                };
        request.setContentType("application/json");
        request.addHeader("Transfer-Encoding", "chunked");
        request.setContent(body);
        return request;
    }

    private static byte[] utf8(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
