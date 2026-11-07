package com.automatedcodereviewtool.security;

import com.automatedcodereviewtool.logging.SecurityEventLogger;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Per-IP rate limiter for the public auth flow endpoints.
 *
 * <p>Applies to {@code /api/auth/github}, {@code /api/auth/callback}, and
 * {@code /api/auth/refresh}. The limit is 10 requests per minute for each
 * client IP, counted in Redis at {@code ratelimit:auth:{clientIp}} with a
 * 60-second TTL set on the first increment.</p>
 *
 * <p>The client IP is the direct peer ({@code remoteAddr}) unless that peer
 * is listed in {@code app.ratelimit.auth.trusted-proxies} (comma-separated
 * IPs or CIDR ranges; empty by default). Only then is the <em>last</em>
 * {@code X-Forwarded-For} entry — the one appended by that proxy — used;
 * earlier entries are supplied by the client and could be forged. Trusting
 * whole private ranges would let anything on the Docker bridge network
 * spoof the header, so proxies must be named explicitly.</p>
 *
 * <p>When {@code app.ratelimit.auth.client-ip-header} is set (e.g.
 * {@code True-Client-IP} behind Cloudflare → Render, where the last
 * {@code X-Forwarded-For} hop is a Cloudflare edge) and the peer is a trusted
 * proxy, that header's value is used instead, provided it is a valid IP
 * literal; otherwise the logic above applies.</p>
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
    private final SecurityEventLogger securityEventLogger;
    private final List<IpAddressMatcher> trustedProxies;
    private final String clientIpHeader;

    public AuthRateLimitFilter(
            StringRedisTemplate redis,
            @Value("${app.ratelimit.auth.requests-per-minute:10}") int requestsPerMinute,
            @Value("${app.ratelimit.auth.trusted-proxies:}") List<String> trustedProxies,
            @Value("${app.ratelimit.auth.client-ip-header:}") String clientIpHeader,
            SecurityEventLogger securityEventLogger) {
        this.redis = redis;
        this.requestsPerMinute = requestsPerMinute;
        this.securityEventLogger = securityEventLogger;
        this.trustedProxies = trustedProxies.stream()
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .map(IpAddressMatcher::new)
                .toList();
        this.clientIpHeader = clientIpHeader == null || clientIpHeader.isBlank() ? null : clientIpHeader.trim();
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
            securityEventLogger.logRateLimitEvent(null, clientIp, request.getRequestURI(), "RATE_LIMIT");
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
     *
     * <p>If Redis is unreachable (any exception from INCR/EXPIRE) we
     * fail open and allow the request through — auth availability
     * matters more than enforcing the limit when the limiter itself
     * is down.</p>
     */
    private boolean isRateLimited(String clientIp) {
        String key = RATE_LIMIT_KEY_PREFIX + clientIp;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                log.warn("Redis INCR returned null for {}; failing open", key);
                return false;
            }
            if (count == 1L) {
                try {
                    redis.expire(key, Duration.ofSeconds(60));
                } catch (Exception expireEx) {
                    // EXPIRE is best-effort — the count above is the
                    // authority, and we'd rather risk a 60s+ window
                    // than fail open after INCR succeeded.
                    log.warn("Redis EXPIRE failed for {} ({}); continuing",
                            key, expireEx.getMessage());
                }
            }
            return count > requestsPerMinute;
        } catch (Exception ex) {
            log.warn("Redis rate-limit call failed for {} ({}); failing open",
                    key, ex.getMessage());
            return false;
        }
    }

    /** See the class comment for when a forwarded value is used. */
    String resolveClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (isTrustedProxy(remoteAddr)) {
            if (clientIpHeader != null) {
                String headerIp = ipLiteral(request.getHeader(clientIpHeader));
                if (headerIp != null) {
                    return headerIp;
                }
            }
            String forwarded = request.getHeader(X_FORWARDED_FOR);
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.lastIndexOf(',');
                String last = (comma == -1 ? forwarded : forwarded.substring(comma + 1)).trim();
                if (!last.isEmpty()) {
                    return last;
                }
            }
        }
        return remoteAddr;
    }

    private static final java.util.regex.Pattern IPV4 = java.util.regex.Pattern.compile(
            "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
    private static final java.util.regex.Pattern IPV6_CHARS = java.util.regex.Pattern.compile("[0-9A-Fa-f:.]+");

    /**
     * The normalized address if {@code value} is an IPv4 or IPv6 literal, else
     * {@code null}. Only literal-shaped input reaches {@link java.net.InetAddress},
     * so this never triggers a DNS lookup.
     */
    static String ipLiteral(String value) {
        if (value == null) return null;
        String candidate = value.trim();
        if (IPV4.matcher(candidate).matches()) {
            return candidate;
        }
        if (candidate.indexOf(':') >= 0 && IPV6_CHARS.matcher(candidate).matches()) {
            try {
                return java.net.InetAddress.getByName(candidate).getHostAddress();
            } catch (java.net.UnknownHostException ex) {
                return null;
            }
        }
        return null;
    }

    private boolean isTrustedProxy(String ip) {
        if (ip == null || ip.isBlank() || trustedProxies.isEmpty()) return false;
        try {
            return trustedProxies.stream().anyMatch(matcher -> matcher.matches(ip));
        } catch (IllegalArgumentException ex) {
            return false; // not an IP literal
        }
    }

    private void tooManyRequests(HttpServletResponse res) throws IOException {
        res.setStatus(429);
        res.setContentType("application/json");
        res.getWriter().write(
                "{\"error\":\"RATE_LIMIT_EXCEEDED\",\"retryAfter\":60}");
    }
}
