package com.codelens.security;

import com.codelens.config.SecurityConfig;
import com.codelens.logging.SecurityEventLogger;
import com.codelens.monitoring.SecurityMonitor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Comprehensive security integration tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SecurityConfig.class)
public class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SecurityEventLogger securityEventLogger;

    @MockBean
    private SecurityMonitor securityMonitor;

    @Test
    void testCSRFProtection() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        verify(securityEventLogger).logSecurityViolation(
                "CSRF_PROTECTION", "Missing CSRF token", "unknown");
    }

    @Test
    void testRequestSizeLimit() throws Exception {
        // Create a large request body
        StringBuilder largeBody = new StringBuilder();
        for (int i = 0; i < 100000; i++) {
            largeBody.append("This is a test line that should exceed the limit. ");
        }

        mockMvc.perform(post("/api/auth/api-key")
                .content(largeBody.toString())
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is4xxClientError());

        verify(securityMonitor).recordSecurityViolation(
                "REQUEST_TOO_LARGE", any(String.class), "unknown");
    }

    @Test
    void testSecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/dashboard"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy",
                    "default-src 'self'"))
                .andExpect(header().string("Strict-Transport-Security",
                    "max-age=31536000"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().doesNotExist("Server"));
    }

    @Test
    void testRedactionSensitiveData() throws Exception {
        // Test sensitive data in headers
        mockMvc.perform(get("/api/dashboard")
                .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."))
                .andExpect(status().isOk());

        // Verify that sensitive data was redacted in logs
        verify(securityEventLogger).logSecurityViolation(
                "HEADER_CONTAINS_TOKEN", "Authorization header redacted", "unknown");
    }

    @Test
    void testRateLimiting() throws Exception {
        // Make many rapid requests
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/dashboard"));
        }

        // Should still be successful
        mockMvc.perform(get("/api/dashboard"))
                .andExpect(status().isOk());

        // Rate limit event should be logged
        verify(securityMonitor).recordRateLimitEvent(
                any(), any(String.class), any(String.class), "RATE_LIMIT");
    }

    @Test
    void testJWTBlacklisting() throws Exception {
        // First get a valid token
        MvcResult result = mockMvc.perform(post("/api/auth/api-key")
                .content("{\"userId\":\"test-user-id\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String token = extractToken(result);

        // Use the token to make a request
        mockMvc.perform(get("/api/dashboard")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Now blacklist the token (simulate logout)
        mockMvc.perform(post("/api/auth/logout"));

        // The token should be rejected
        mockMvc.perform(get("/api/dashboard")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        verify(securityEventLogger).logTokenBlacklisting(
                any(String.class), any(UUID.class), "logout");
    }

    @Test
    void testCircuitBreaker() throws Exception {
        // Simulate multiple failures
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/api/ml/analyze")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("invalid"))
                    .andExpect(status().is5xxServerError());
        }

        // Circuit breaker should open and return 503
        mockMvc.perform(get("/api/ml/analyze")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable());

        verify(securityMonitor).recordSecurityViolation(
                "CIRCUIT_BREAKER_OPEN", "Circuit breaker opened for ML service", "unknown");
    }

    @Test
    void testWebhookSecurity() throws Exception {
        // Test webhook with invalid signature
        String webhookPayload = "{\"action\":\"opened\",\"repository\":{\"id\":123}}";
        String signature = "invalid-signature";

        mockMvc.perform(post("/api/webhook/github")
                .content(webhookPayload)
                .header("X-Hub-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(securityEventLogger).logWebhookSecurityEvent(
                "123", false, "unknown", "opened");
    }

    /**
     * Helper method to extract token from response.
     */
    private String extractToken(MvcResult result) throws Exception {
        String response = result.getResponse().getContentAsString();
        // Simple extraction - in real scenario, use proper JWT parsing
        return response.split("\"token\":\"")[1].split("\"")[0];
    }
}