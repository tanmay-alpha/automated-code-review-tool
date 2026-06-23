package com.codelens.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Per-IP rate limiter for the public auth flow endpoints.
 *
 * <p>Applies to {@code /api/auth/github}, {@code /api/auth/callback}, and
 * {@code /api/auth/refresh} — the three routes the OAuth handshake hits
 * repeatedly. Spec'd at 10 requests/minute per client IP (ENGINEERING_PLAN.md
 * §8).</p>
 *
 * <p>IP is extracted from {@code X-Forwarded-For} when present (taking the
 * first hop) and falls back to {@link HttpServletRequest#getRemoteAddr()}.
 * The counter lives in Redis at {@code ratelimit:auth:{clientIp}} with a
 * 60-second TTL set on the first increment.</p>
 *
 * <p>This filter is registered <strong>before</strong> the JWT filter so
 * brute-force attacks on the auth flow are shed before any token work
 * happens, and <strong>after</strong> the API key filter so VS Code
 * extension traffic is unaffected.</p>
 */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);

    /** Header we trust for the real client IP when behind a proxy / load balancer. */
    public static final String X_FORWARDED_FOR = "X-Forwarded-For";

    /** Redis key prefix for the per-IP auth bucket. */
    public static final String RATE_LIMIT_KEY_PREFIX = "ratelimit:auth:";

    /** Routes this filter guards. Matched as exact {@code requestURI} values. */
    private static final List<String> PROTECTED_PATHS = List.of(
            "/api/auth/github",
            "/api/auth/callback",
            "/api/auth/refresh"
    );

    private final StringRedisTemplate redis;
    private final int requestsPerMinute;

    public AuthRateLimitFilter(
            StringRedisTemplate redis,
            @Value("${app.ratelimit.auth.requests-per-minute:10}") int requestsPerMinute) {
        this.redis = redis;
        this.requestsPerMinute = requestsPerMinute;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !PROTECTED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        String clientIp = resolveClientIp(request);
        if (isRateLimited(clientIp)) {
            tooManyRequests(response);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * INCR-then-EXPIRE on the per-IP bucket. The first hit creates the key
     * and sets a 60s TTL; subsequent hits in the same window just
     * increment. If the count exceeds the limit we reject.
     *
     * <p>Worst-case race: two requests both read count=10, both accept.
     * That's acceptable for a 10-RPM auth limit.</p>
     */
    private boolean isRateLimited(String clientIp) {
        String key = RATE_LIMIT_KEY_PREFIX + clientIp;
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            // Redis down — fail open so a Redis outage doesn't lock
            // every user out of the OAuth flow.
            log.warn("Redis INCR returned null for {}; failing open", key);
            return false;
        }
        if (count == 1L) {
            redis.expire(key, Duration.ofSeconds(60));
        }
        return count > requestsPerMinute;
    }

    /**
     * X-Forwarded-For is a comma-separated chain of IPs in order
     * client, proxy1, proxy2; the first entry is the original caller.
     * Falls back to {@code remoteAddr} if the header is absent.
     */
    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader(X_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma == -1 ? forwarded : forwarded.substring(0, comma)).trim();
        }
        return request.getRemoteAddr();
    }

    private void tooManyRequests(HttpServletResponse res) throws IOException {
        res.setStatus(429);
        res.setContentType("application/json");
        res.getWriter().write(
                "{\"error\":\"RATE_LIMIT_EXCEEDED\",\"retryAfter\":60}");
    }
}
