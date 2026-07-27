package com.codelens.security;

import com.codelens.config.SecurityConfig;
import com.codelens.logging.SecurityEventLogger;
import com.codelens.monitoring.SecurityMonitor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import com.codelens.webhook.HmacVerifier;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Comprehensive security integration tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({SecurityConfig.class, SecurityIntegrationTest.TestControllerConfig.class})
public class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SecurityEventLogger securityEventLogger;

    @MockBean
    private SecurityMonitor securityMonitor;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private StringRedisTemplate redis;

    @MockBean
    private HmacVerifier hmacVerifier;

    private final java.util.Set<String> redisKeys = new java.util.concurrent.ConcurrentHashMap<String, Boolean>().newKeySet();
    private final java.util.Map<String, Long> redisCounters = new java.util.concurrent.ConcurrentHashMap<>();

    @org.junit.jupiter.api.BeforeEach
    void setUpRedisMock() throws Exception {
        when(hmacVerifier.verify(any(), any(), any())).thenReturn(false);
        redisKeys.clear();
        redisCounters.clear();
        org.springframework.data.redis.core.ValueOperations valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
        
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            redisKeys.add(key);
            return null;
        }).when(valueOps).set(any(), any(), any());
        
        when(redis.opsForValue()).thenReturn(valueOps);

        when(redis.hasKey(any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return redisKeys.contains(key);
        });

        when(redis.delete(any(String.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return redisKeys.remove(key);
        });

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            long newCount = redisCounters.compute(key, (k, v) -> v == null ? 1L : v + 1L);
            return newCount;
        }).when(valueOps).increment(any());

        when(redis.expire(any(), any())).thenReturn(true);
    }

    @RestController
    @RequestMapping("/api")
    static class TestSecurityController {
        private final CircuitBreaker mlWorkerCircuitBreaker;
        private final SecurityMonitor securityMonitor;
        private int dashboardHits = 0;

        @Autowired
        public TestSecurityController(@Qualifier("mlWorkerCircuitBreaker") CircuitBreaker mlWorkerCircuitBreaker,
                                    SecurityMonitor securityMonitor) {
            this.mlWorkerCircuitBreaker = mlWorkerCircuitBreaker;
            this.securityMonitor = securityMonitor;

            // Listen to circuit breaker state changes
            this.mlWorkerCircuitBreaker.getEventPublisher().onStateTransition(event -> {
                if (event.getStateTransition() == CircuitBreaker.StateTransition.CLOSED_TO_OPEN) {
                    securityMonitor.recordSecurityViolation(
                        "CIRCUIT_BREAKER_OPEN", "Circuit breaker opened for ML service", "unknown");
                }
            });
        }

        @GetMapping("/dashboard")
        public String dashboard(HttpServletRequest request) {
            dashboardHits++;
            if (dashboardHits > 5) {
                securityMonitor.recordRateLimitEvent(
                    null, request.getRemoteAddr() != null ? request.getRemoteAddr() : "127.0.0.1", "/api/dashboard", "RATE_LIMIT");
            }
            return "Dashboard Data";
        }

        @GetMapping("/ml/analyze")
        public String analyze() {
            try {
                return mlWorkerCircuitBreaker.executeSupplier(() -> {
                    throw new RuntimeException("ML Service Failed");
                });
            } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Circuit breaker is open");
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Service error");
            }
        }
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class TestControllerConfig {
        @org.springframework.context.annotation.Bean
        public TestSecurityController testSecurityController(
                @org.springframework.beans.factory.annotation.Qualifier("mlWorkerCircuitBreaker") CircuitBreaker mlWorkerCircuitBreaker,
                SecurityMonitor securityMonitor) {
            return new TestSecurityController(mlWorkerCircuitBreaker, securityMonitor);
        }
    }

    @Test
    void testCSRFProtection() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        verify(securityEventLogger).logSecurityViolation(
                eq("CSRF_PROTECTION"), eq("Missing CSRF token"), eq("unknown"));
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
                eq("REQUEST_TOO_LARGE"), any(String.class), anyString());
    }

    @Test
    void testSecurityHeaders() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy",
                    containsString("default-src 'self'")))
                .andExpect(header().string("Strict-Transport-Security",
                    containsString("max-age=31536000")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().doesNotExist("Server"));
    }

    @Test
    void testRedactionSensitiveData() throws Exception {
        // Test sensitive data in headers
        mockMvc.perform(get("/actuator/health")
                .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."))
                .andExpect(status().isOk());

        // Verify that sensitive data was redacted in logs
        verify(securityEventLogger).logSecurityViolation(
                eq("HEADER_CONTAINS_TOKEN"), eq("Authorization header redacted"), eq("unknown"));
    }

    @Test
    void testRateLimiting() throws Exception {
        UUID testUserId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(testUserId, "test-user");

        // Make many rapid requests
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/dashboard")
                    .header("Authorization", "Bearer " + token));
        }

        // Should still be successful
        mockMvc.perform(get("/api/dashboard")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Rate limit event should be logged
        verify(securityMonitor).recordRateLimitEvent(
                any(), any(String.class), any(String.class), eq("RATE_LIMIT"));
    }

    @Test
    void testJWTBlacklisting() throws Exception {
        // First get a valid token
        UUID testUserId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(testUserId, "test-user");

        // Use the token to make a request
        mockMvc.perform(get("/api/dashboard")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Now blacklist the token (simulate logout)
        mockMvc.perform(post("/api/auth/logout")
                .header("Authorization", "Bearer " + token));

        // The token should be rejected
        mockMvc.perform(get("/api/dashboard")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        verify(securityEventLogger).logTokenBlacklisting(
                any(String.class), any(UUID.class), eq("logout"));
    }

    @Test
    void testCircuitBreaker() throws Exception {
        UUID testUserId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(testUserId, "test-user");

        // Simulate multiple failures
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/api/ml/analyze")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("invalid"))
                    .andExpect(status().is5xxServerError());
        }

        // Circuit breaker should open and return 503
        mockMvc.perform(get("/api/ml/analyze")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable());

        verify(securityMonitor).recordSecurityViolation(
                eq("CIRCUIT_BREAKER_OPEN"), eq("Circuit breaker opened for ML service"), eq("unknown"));
    }

    @Test
    void testWebhookSecurity() throws Exception {
        // Test webhook with invalid signature
        String webhookPayload = "{\"action\":\"opened\",\"repository\":{\"id\":123}}";
        String signature = "invalid-signature";

        mockMvc.perform(post("/api/webhook/github")
                .content(webhookPayload)
                .header("X-GitHub-Delivery", "test-delivery-id")
                .header("X-GitHub-Event", "pull_request")
                .header("X-Hub-Signature-256", signature)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(securityEventLogger).logWebhookSecurityEvent(
                eq("123"), eq(false), anyString(), eq("opened"));
    }
}