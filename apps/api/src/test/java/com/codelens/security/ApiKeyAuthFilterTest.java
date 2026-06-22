package com.codelens.security;

import com.codelens.entity.ApiKey;
import com.codelens.entity.User;
import com.codelens.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link ApiKeyAuthFilter}.
 *
 * <p>Drives the filter directly (no Spring context) with a mocked
 * {@link ApiKeyService} and a mocked Redis template. Verifies the
 * pass-through behavior on non-{@code /api/scan/file} requests, the
 * rejection cases, the happy path, and the rate-limit path.</p>
 */
class ApiKeyAuthFilterTest {

    private ApiKeyService apiKeyService;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ApiKeyAuthFilter filter;

    private static final String PREFIX = "cl_live_abcd1234";
    private static final String FULL_KEY = PREFIX + "0".repeat(24); // 32 hex chars after literal

    @BeforeEach
    void setUp() {
        apiKeyService = mock(ApiKeyService.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        filter = new ApiKeyAuthFilter(apiKeyService, redis, 60);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---- pass-through (non-scan-file path) -------------------------------

    @Test
    void nonScanFileRequest_isIgnored_andChainContinues() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/reviews/abc");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<String> downstream = new AtomicReference<>();
        FilterChain chain = (request, response) -> downstream.set("hit");

        filter.doFilter(req, res, chain);

        assertThat(downstream.get()).isEqualTo("hit");
        // No auth attempt made
        verify(apiKeyService, never()).findByPrefix(anyString());
    }

    // ---- pass-through (no key header → JWT filter takes over) -----------

    @Test
    void scanFile_withNoAuthHeader_fallsThroughToJwtFilter() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/scan/file");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<String> downstream = new AtomicReference<>();
        FilterChain chain = (request, response) -> downstream.set("jwt-passed");

        filter.doFilter(req, res, chain);

        assertThat(downstream.get()).isEqualTo("jwt-passed");
    }

    @Test
    void scanFile_withNonBearerScheme_fallsThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/scan/file");
        req.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<String> downstream = new AtomicReference<>();
        FilterChain chain = (request, response) -> downstream.set("jwt-passed");

        filter.doFilter(req, res, chain);

        assertThat(downstream.get()).isEqualTo("jwt-passed");
    }

    @Test
    void scanFile_withNonApiKeyBearer_fallsThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/scan/file");
        req.addHeader("Authorization", "Bearer some.jwt.here");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<String> downstream = new AtomicReference<>();
        FilterChain chain = (request, response) -> downstream.set("jwt-passed");

        filter.doFilter(req, res, chain);

        assertThat(downstream.get()).isEqualTo("jwt-passed");
    }

    // ---- 403: bad key ----------------------------------------------------

    @Test
    void scanFile_withUnknownKeyPrefix_returns403() throws Exception {
        when(apiKeyService.findByPrefix(anyString())).thenReturn(null);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/scan/file");
        req.addHeader("Authorization", "Bearer " + FULL_KEY);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("API_KEY_INVALID");
    }

    @Test
    void scanFile_withRevokedKey_returns403() throws Exception {
        ApiKey stored = newKey(true);
        when(apiKeyService.findByPrefix(PREFIX)).thenReturn(stored);
        when(apiKeyService.verify(stored, FULL_KEY)).thenReturn(false);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/scan/file");
        req.addHeader("Authorization", "Bearer " + FULL_KEY);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    void scanFile_withShortKey_returns403() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/scan/file");
        req.addHeader("Authorization", "Bearer " + ApiKeyService.KEY_PREFIX_LITERAL + "abc");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
    }

    // ---- 429: rate-limited -----------------------------------------------

    @Test
    void scanFile_overRateLimit_returns429() throws Exception {
        User user = User.builder().id(UUID.randomUUID()).githubUsername("alice").build();
        ApiKey stored = newKey(false);
        stored.setUser(user);
        when(apiKeyService.findByPrefix(PREFIX)).thenReturn(stored);
        when(apiKeyService.verify(stored, FULL_KEY)).thenReturn(true);
        when(valueOps.increment(anyString())).thenReturn(61L);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/scan/file");
        req.addHeader("Authorization", "Bearer " + FULL_KEY);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isEqualTo("60");
    }

    @Test
    void scanFile_underRateLimit_setsExpiryOnFirstHit() throws Exception {
        User user = User.builder().id(UUID.randomUUID()).githubUsername("alice").build();
        ApiKey stored = newKey(false);
        stored.setUser(user);
        when(apiKeyService.findByPrefix(PREFIX)).thenReturn(stored);
        when(apiKeyService.verify(stored, FULL_KEY)).thenReturn(true);
        when(valueOps.increment(anyString())).thenReturn(1L);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/scan/file");
        req.addHeader("Authorization", "Bearer " + FULL_KEY);
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<Authentication> seen = new AtomicReference<>();
        FilterChain chain = (r, s) -> {
            seen.set(SecurityContextHolder.getContext().getAuthentication());
        };

        filter.doFilter(req, res, chain);

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().getAuthorities())
                .extracting(Object::toString)
                .contains("ROLE_API_KEY");
        verify(redis).expire(anyString(), any(Duration.class));
    }

    // ---- helper ----------------------------------------------------------

    private static ApiKey newKey(boolean revoked) {
        return ApiKey.builder()
                .id(UUID.randomUUID())
                .label("test")
                .prefix(PREFIX)
                .keyHash("$2a$10$dummy")
                .revoked(revoked)
                .createdAt(Instant.now())
                .build();
    }
}