package com.codelens.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link AuthRateLimitFilter}.
 *
 * <p>Drives the filter directly (no Spring context) with a mocked
 * {@link StringRedisTemplate}. Verifies the three contracts called out
 * in the spec:</p>
 * <ul>
 *   <li>Under-limit traffic flows through and the bucket TTL is set
 *       on the first hit.</li>
 *   <li>Over-limit traffic is shed with a 429 and the spec'd JSON body.</li>
 *   <li>Different IPs land on different Redis keys (no cross-tenant
 *       contamination).</li>
 * </ul>
 */
class AuthRateLimitFilterTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private AuthRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        filter = new AuthRateLimitFilter(redis, 10);
    }

    // ---- testUnderLimitPasses --------------------------------------------

    @Test
    void underLimitPasses() throws Exception {
        // First hit creates the bucket (count=1); the filter must
        // call expire(60s) and forward the request down the chain.
        when(valueOps.increment(AuthRateLimitFilter.RATE_LIMIT_KEY_PREFIX + "1.2.3.4"))
                .thenReturn(1L);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/auth/github");
        req.setRemoteAddr("1.2.3.4");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<String> downstream = new AtomicReference<>();
        FilterChain chain = (request, response) -> downstream.set("hit");

        filter.doFilter(req, res, chain);

        // The chain was reached.
        assertThat(downstream.get()).isEqualTo("hit");
        assertThat(res.getStatus()).isEqualTo(200); // default OK

        // The 60s TTL was applied to the per-IP key.
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCap = ArgumentCaptor.forClass(Duration.class);
        verify(redis).expire(keyCap.capture(), ttlCap.capture());
        assertThat(keyCap.getValue())
                .isEqualTo(AuthRateLimitFilter.RATE_LIMIT_KEY_PREFIX + "1.2.3.4");
        assertThat(ttlCap.getValue()).isEqualTo(Duration.ofSeconds(60));
    }

    // ---- testOverLimitReturns429 -----------------------------------------

    @Test
    void overLimitReturns429() throws Exception {
        // Eleventh hit in the same minute → reject.
        when(valueOps.increment(AuthRateLimitFilter.RATE_LIMIT_KEY_PREFIX + "1.2.3.4"))
                .thenReturn(11L);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/callback");
        req.setRemoteAddr("1.2.3.4");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<String> downstream = new AtomicReference<>();
        FilterChain chain = (request, response) -> downstream.set("should-not-fire");

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getContentAsString())
                .isEqualTo("{\"error\":\"RATE_LIMIT_EXCEEDED\",\"retryAfter\":60}");
        // The chain was not invoked.
        assertThat(downstream.get()).isNull();
        // The TTL is only set on the bucket's first hit, so a count=11
        // (bucket already exists) should not call expire again.
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    // ---- testDifferentIpsHaveSeparateLimits ------------------------------

    @Test
    void differentIpsHaveSeparateLimits() throws Exception {
        // IP A is at the limit; IP B is fresh. Each must land on its
        // own Redis key and only IP A should be rejected.
        when(valueOps.increment(AuthRateLimitFilter.RATE_LIMIT_KEY_PREFIX + "1.1.1.1"))
                .thenReturn(11L);
        when(valueOps.increment(AuthRateLimitFilter.RATE_LIMIT_KEY_PREFIX + "2.2.2.2"))
                .thenReturn(1L);

        // IP A → 429
        MockHttpServletRequest reqA = new MockHttpServletRequest("POST", "/api/auth/refresh");
        reqA.setRemoteAddr("1.1.1.1");
        MockHttpServletResponse resA = new MockHttpServletResponse();
        AtomicReference<String> aHit = new AtomicReference<>();
        filter.doFilter(reqA, resA, (r, s) -> aHit.set("A-fired"));

        assertThat(resA.getStatus()).isEqualTo(429);
        assertThat(aHit.get()).isNull();

        // IP B → 200
        MockHttpServletRequest reqB = new MockHttpServletRequest("POST", "/api/auth/refresh");
        reqB.setRemoteAddr("2.2.2.2");
        MockHttpServletResponse resB = new MockHttpServletResponse();
        AtomicReference<String> bHit = new AtomicReference<>();
        filter.doFilter(reqB, resB, (r, s) -> bHit.set("B-fired"));

        assertThat(resB.getStatus()).isEqualTo(200);
        assertThat(bHit.get()).isEqualTo("B-fired");

        // Each call hit its own key — no cross-contamination.
        verify(valueOps).increment(AuthRateLimitFilter.RATE_LIMIT_KEY_PREFIX + "1.1.1.1");
        verify(valueOps).increment(AuthRateLimitFilter.RATE_LIMIT_KEY_PREFIX + "2.2.2.2");
    }
}
