package com.codelens.controller;

import com.codelens.config.SecurityConfig;
import com.codelens.repository.ProcessedWebhookRepository;
import com.codelens.security.ApiKeyAuthFilter;
import com.codelens.security.JwtAuthFilter;
import com.codelens.security.JwtService;
import com.codelens.service.ApiKeyService;
import com.codelens.service.WebhookService;
import com.codelens.webhook.GitHubWebhookEvent;
import com.codelens.webhook.HmacVerificationException;
import com.codelens.webhook.HmacVerifier;
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
 * HmacVerifier, WebhookService, and ProcessedWebhookRepository are
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

    @MockBean HmacVerifier hmacVerifier;
    @MockBean WebhookService webhookService;
    @MockBean ProcessedWebhookRepository processedWebhookRepository;
    // SecurityConfig wires JwtAuthFilter which needs JwtService; stub both
    // with a pass-through filter so the chain actually calls into the
    // controller (a Mockito mock filter would do nothing and stall the
    // request).
    @MockBean JwtService jwtService;
    // SecurityConfig also now requires ApiKeyAuthFilter — mock it so the
    // MVC slice doesn't try to load Redis / ApiKeyRepository.
    @MockBean ApiKeyAuthFilter apiKeyAuthFilter;
    @MockBean ApiKeyService apiKeyService;

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
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class StubFilterConfig {
        @org.springframework.context.annotation.Bean
        JwtAuthFilter jwtAuthFilter() {
            return new JwtAuthFilter(null) {
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
        when(hmacVerifier.verify(eq(body), anyString(), eq("123"))).thenReturn(true);
        when(processedWebhookRepository.existsById(DELIVERY)).thenReturn(false);

        mockMvc.perform(post("/api/webhook/github")
                        .header("X-Hub-Signature-256", "sha256=valid")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", DELIVERY)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        verify(webhookService, times(1))
                .processAsync(any(GitHubWebhookEvent.class), eq("123"));
        verify(processedWebhookRepository, times(1)).save(any());
    }

    @Test
    void testForgedSignatureReturns401() throws Exception {
        String body = payload("opened", 123L);
        when(hmacVerifier.verify(anyString(), anyString(), anyString())).thenReturn(false);

        mockMvc.perform(post("/api/webhook/github")
                        .header("X-Hub-Signature-256", "sha256=forged")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", DELIVERY)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnauthorized());

        verify(webhookService, never()).processAsync(any(), anyString());
    }

    @Test
    void testDuplicateDeliveryIdReturns200WithNoProcessing() throws Exception {
        String body = payload("opened", 123L);
        when(hmacVerifier.verify(eq(body), anyString(), eq("123"))).thenReturn(true);
        when(processedWebhookRepository.existsById(DELIVERY)).thenReturn(true);

        mockMvc.perform(post("/api/webhook/github")
                        .header("X-Hub-Signature-256", "sha256=valid")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", DELIVERY)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        verify(webhookService, never()).processAsync(any(), anyString());
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

        verify(hmacVerifier, never()).verify(anyString(), anyString(), anyString());
        verify(webhookService, never()).processAsync(any(), anyString());
    }

    @Test
    void testUnknownRepoReturns200() throws Exception {
        // HmacVerifier throws when we have no secret for the repo — that means
        // the webhook isn't for one of our installed repos, so 200 + ignore.
        String body = payload("opened", 123L);
        when(hmacVerifier.verify(eq(body), anyString(), eq("123")))
                .thenThrow(new HmacVerificationException("no repo"));

        mockMvc.perform(post("/api/webhook/github")
                        .header("X-Hub-Signature-256", "sha256=valid")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", DELIVERY)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        verify(webhookService, never()).processAsync(any(), anyString());
    }
}