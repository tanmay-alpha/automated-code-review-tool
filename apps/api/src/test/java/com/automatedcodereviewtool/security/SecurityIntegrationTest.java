package com.automatedcodereviewtool.security;

import com.automatedcodereviewtool.logging.SecurityEventLogger;
import com.automatedcodereviewtool.repository.UserRepository;
import com.automatedcodereviewtool.webhook.HmacVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
 * Full filter-chain tests (real JWT, size-limit, header and webhook
 * handling) with Redis replaced by an in-memory mock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SecurityIntegrationTest.TestControllerConfig.class)
class SecurityIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;

    @MockBean private SecurityEventLogger securityEventLogger;
    @MockBean private StringRedisTemplate redis;
    @MockBean private HmacVerifier hmacVerifier;
    @MockBean private UserRepository userRepository;
    @MockBean private GitHubAuthorizationVerifier gitHubAuthorizationVerifier;

    private final Set<String> redisKeys = ConcurrentHashMap.newKeySet();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUpRedisMock() {
        when(hmacVerifier.verify(any(), any(), any())).thenReturn(false);
        when(gitHubAuthorizationVerifier.check(any())).thenReturn(GitHubAuthorizationVerifier.Status.VALID);
        when(userRepository.findById(any())).thenAnswer(inv -> Optional.of(
                com.automatedcodereviewtool.entity.User.builder()
                        .id(inv.getArgument(0))
                        .githubUsername("test-user")
                        .accessToken("dummy")
                        .build()));
        redisKeys.clear();

        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        doAnswer(inv -> {
            redisKeys.add(inv.getArgument(0));
            return null;
        }).when(valueOps).set(any(), any(), any());
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.hasKey(any())).thenAnswer(inv -> redisKeys.contains(inv.<String>getArgument(0)));
        when(redis.delete(any(String.class))).thenAnswer(inv -> redisKeys.remove(inv.<String>getArgument(0)));
    }

    /** A trivial authenticated endpoint to probe the JWT filter with. */
    @RestController
    static class ProtectedTestController {
        @GetMapping("/api/test/protected")
        String protectedEndpoint() {
            return "ok";
        }
    }

    @TestConfiguration
    static class TestControllerConfig {
        @Bean
        ProtectedTestController protectedTestController() {
            return new ProtectedTestController();
        }
    }

    @Test
    void oversizedRequestBodyIsRejected() throws Exception {
        String largeBody = "x".repeat(10_000); // test profile limit: 5000 bytes

        mockMvc.perform(post("/api/scan/file")
                        .content(largeBody)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(securityEventLogger).logSecurityViolation(
                eq("REQUEST_TOO_LARGE"), anyString(), anyString());
    }

    @Test
    void securityHeadersArePresent() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'none'; frame-ancestors 'none'; object-src 'none'; base-uri 'none'"))
                .andExpect(header().string("Strict-Transport-Security", containsString("max-age=31536000")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().doesNotExist("Server"));
    }

    @Test
    void loggedOutAccessTokenIsRejected() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(userId, "test-user", 0);

        mockMvc.perform(get("/api/test/protected").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/test/protected").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        verify(securityEventLogger).logTokenBlacklisting(anyString(), eq(userId), eq("logout"));
    }

    @Test
    void refreshTokenIsNotAcceptedAsAccessToken() throws Exception {
        String refresh = jwtService.generateRefreshToken(UUID.randomUUID(), 0);

        mockMvc.perform(get("/api/test/protected").header("Authorization", "Bearer " + refresh))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accessTokenIsRejectedWith503WhenRevocationCheckIsUnavailable() throws Exception {
        doThrow(new RedisConnectionFailureException("redis down")).when(redis).hasKey(any());
        String token = jwtService.generateAccessToken(UUID.randomUUID(), "test-user", 0);

        mockMvc.perform(get("/api/test/protected").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("AUTH_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    void refreshFailsClosedWhenSessionStoreIsUnavailable() throws Exception {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        String refresh = jwtService.generateRefreshToken(UUID.randomUUID(), 0);

        mockMvc.perform(post("/api/auth/refresh").cookie(new jakarta.servlet.http.Cookie("__Host-refreshToken", refresh))
                        .header("Origin", "http://localhost:3000"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    void refreshWithTokenThatIsNotTheCurrentSessionIsRejected() throws Exception {
        // The compare-and-set script found a different token (and revoked the session).
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);
        String refresh = jwtService.generateRefreshToken(UUID.randomUUID(), 0);

        mockMvc.perform(post("/api/auth/refresh").cookie(new jakarta.servlet.http.Cookie("__Host-refreshToken", refresh))
                        .header("Origin", "http://localhost:3000"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void refreshRotatesSessionWithSingleCompareAndSet() throws Exception {
        UUID userId = UUID.randomUUID();
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        String refresh = jwtService.generateRefreshToken(userId, 0);

        mockMvc.perform(post("/api/auth/refresh").cookie(new jakarta.servlet.http.Cookie("__Host-refreshToken", refresh))
                        .header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(header().stringValues("Set-Cookie",
                        hasItem(startsWith("__Host-refreshToken="))));

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object> rotatedTo = ArgumentCaptor.forClass(Object.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(),
                eq(refresh), rotatedTo.capture(), eq("604800"));
        assertThat(keys.getValue()).containsExactly("session:refresh:" + userId);
        assertThat(rotatedTo.getValue()).isNotEqualTo(refresh);
        // No separate read-then-write: the rotation is one atomic script call.
        verify(redis.opsForValue(), never()).get(anyString());
        verify(redis.opsForValue(), never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void logoutFailsClosedWhenBlacklistWriteFails() throws Exception {
        String token = jwtService.generateAccessToken(UUID.randomUUID(), "test-user", 0);
        ValueOperations<String, String> valueOps = redis.opsForValue();
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void logoutFailsClosedWhenRefreshSessionDeleteFails() throws Exception {
        String token = jwtService.generateAccessToken(UUID.randomUUID(), "test-user", 0);
        when(redis.delete(any(String.class))).thenThrow(new RedisConnectionFailureException("redis down"));

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void webhookWithInvalidSignatureIsRejected() throws Exception {
        String webhookPayload = "{\"action\":\"opened\",\"repository\":{\"id\":123}}";

        mockMvc.perform(post("/api/webhook/github")
                        .content(webhookPayload)
                        .header("X-GitHub-Delivery", "test-delivery-id")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-Hub-Signature-256", "sha256=invalid")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(securityEventLogger).logWebhookSecurityEvent(
                eq("123"), eq(false), anyString(), eq("opened"));
    }
}
