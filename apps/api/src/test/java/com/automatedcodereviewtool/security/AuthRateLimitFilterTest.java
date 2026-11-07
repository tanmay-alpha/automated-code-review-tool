package com.automatedcodereviewtool.security;

import com.automatedcodereviewtool.logging.SecurityEventLogger;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.data.redis.RedisConnectionFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link AuthRateLimitFilter}, driven directly (no Spring
 * context) with a mocked {@link StringRedisTemplate}:
 * <ul>
 *   <li>Under-limit traffic flows through and the bucket TTL is set
 *       on the first hit.</li>
 *   <li>Over-limit traffic is shed with a 429 JSON body.</li>
 *   <li>Different IPs land on different Redis keys.</li>
 *   <li>Forwarded client IPs are only trusted from explicitly configured proxies.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthRateLimitFilterTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private SecurityEventLogger securityEventLogger;
    private AuthRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);

        filter = new AuthRateLimitFilter(redis, 10, List.of(), "", securityEventLogger);
    }

    // ---- client IP resolution ---------------------------------------------

    @Test
    void clientIp_behindConfiguredProxy_usesHopAppendedByProxy() {
        AuthRateLimitFilter behindProxy = new AuthRateLimitFilter(
                redis, 10, List.of("10.0.0.5", "192.168.1.0/24"), "", securityEventLogger);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/auth/github");
        req.setRemoteAddr("10.0.0.5");
        // The first entry is whatever the client sent; the proxy appends the real peer.
        req.addHeader(AuthRateLimitFilter.X_FORWARDED_FOR, "6.6.6.6, 1.2.3.4");

        assertThat(behindProxy.resolveClientIp(req)).isEqualTo("1.2.3.4");

        req.setRemoteAddr("192.168.1.77");
        assertThat(behindProxy.resolveClientIp(req)).isEqualTo("1.2.3.4");
    }

    @Test
    void clientIp_ignoresForwardedHeaderFromPublicPeer() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/auth/github");
        req.setRemoteAddr("8.8.8.8");
        req.addHeader(AuthRateLimitFilter.X_FORWARDED_FOR, "6.6.6.6");

        assertThat(filter.resolveClientIp(req)).isEqualTo("8.8.8.8");
    }

    @Test
    void clientIp_ignoresForwardedHeaderFromUnlistedPrivatePeer() {
        // A container on the Docker bridge must not be able to pick its own bucket.
        AuthRateLimitFilter behindProxy = new AuthRateLimitFilter(
                redis, 10, List.of("10.0.0.5"), "", securityEventLogger);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/auth/github");
        req.setRemoteAddr("172.18.0.9");
        req.addHeader(AuthRateLimitFilter.X_FORWARDED_FOR, "6.6.6.6");

        assertThat(behindProxy.resolveClientIp(req)).isEqualTo("172.18.0.9");
        assertThat(filter.resolveClientIp(req)).isEqualTo("172.18.0.9");
    }

    // Render: Client -> Cloudflare -> Render proxy (10.x) -> app. The last XFF
    // hop is a Cloudflare edge, so the client IP comes from True-Client-IP.
    private AuthRateLimitFilter behindCdn() {
        return new AuthRateLimitFilter(redis, 10, List.of("10.0.0.0/8"), "True-Client-IP", securityEventLogger);
    }

    private static MockHttpServletRequest cdnRequest(String peer, String trueClientIp) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/auth/github");
        req.setRemoteAddr(peer);
        req.addHeader(AuthRateLimitFilter.X_FORWARDED_FOR, "203.0.113.7, 104.16.0.1");
        req.addHeader("True-Client-IP", trueClientIp);
        return req;
    }

    @Test
    void clientIpHeader_isHonouredFromTrustedPeer() {
        assertThat(behindCdn().resolveClientIp(cdnRequest("10.1.2.3", "203.0.113.7")))
                .isEqualTo("203.0.113.7");
        assertThat(behindCdn().resolveClientIp(cdnRequest("10.1.2.3", " 2001:db8::1 ")))
                .isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    @Test
    void clientIpHeader_isIgnoredFromUntrustedPeer() {
        assertThat(behindCdn().resolveClientIp(cdnRequest("8.8.8.8", "203.0.113.7")))
                .isEqualTo("8.8.8.8");
    }

    @Test
    void clientIpHeader_invalidValueFallsBackToForwardedFor() {
        for (String invalid : new String[] {"not-an-ip", "evil.example.com", "999.1.1.1", "", "1.2.3.4, 5.6.7.8"}) {
            assertThat(behindCdn().resolveClientIp(cdnRequest("10.1.2.3", invalid)))
                    .as("True-Client-IP=%s", invalid)
                    .isEqualTo("104.16.0.1");
        }
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

    // ---- testRequestUnderLimitPassesThrough -------------------------------

    @Test
    void requestUnderLimitPassesThrough() throws Exception {
        // count=5 is well under the 10/min limit → chain must run.
        when(valueOps.increment(AuthRateLimitFilter.RATE_LIMIT_KEY_PREFIX + "1.2.3.4"))
                .thenReturn(5L);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/auth/github");
        req.setRemoteAddr("1.2.3.4");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<String> downstream = new AtomicReference<>();
        FilterChain chain = (request, response) -> downstream.set("hit");

        filter.doFilter(req, res, chain);

        assertThat(downstream.get()).isEqualTo("hit");
        assertThat(res.getStatus()).isEqualTo(200);
    }

    // ---- testRequestOverLimitReturns429 -----------------------------------

    @Test
    void requestOverLimitReturns429() throws Exception {
        // count=11 → over the 10/min limit → 429 + spec JSON body.
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
        assertThat(downstream.get()).isNull();
    }

    // ---- testNonAuthPathSkipsFilter ---------------------------------------

    @Test
    void nonAuthPathSkipsFilter() throws Exception {
        // /api/repos is NOT in PROTECTED_PATHS — shouldNotFilter must
        // short-circuit so doFilterInternal never runs. No Redis call,
        // chain always invoked regardless of any "limit" we might imagine.
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/repos");
        req.setRemoteAddr("1.2.3.4");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<String> downstream = new AtomicReference<>();
        FilterChain chain = (request, response) -> downstream.set("hit");

        filter.doFilter(req, res, chain);

        assertThat(downstream.get()).isEqualTo("hit");
        assertThat(res.getStatus()).isEqualTo(200);
        verifyNoInteractions(valueOps);
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    // ---- testRedisDownAllowsRequest ---------------------------------------

    @Test
    void redisDownAllowsRequest() throws Exception {
        // Redis unreachable → INCR throws → filter must fail open so a
        // Redis outage doesn't lock every user out of the OAuth flow.
        when(valueOps.increment(AuthRateLimitFilter.RATE_LIMIT_KEY_PREFIX + "1.2.3.4"))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/refresh");
        req.setRemoteAddr("1.2.3.4");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<String> downstream = new AtomicReference<>();
        FilterChain chain = (request, response) -> downstream.set("hit");

        filter.doFilter(req, res, chain);

        assertThat(downstream.get()).isEqualTo("hit");
        assertThat(res.getStatus()).isEqualTo(200);
    }
}
