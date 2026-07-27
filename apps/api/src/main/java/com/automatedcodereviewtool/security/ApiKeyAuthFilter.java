package com.codelens.security;

import com.codelens.entity.ApiKey;
import com.codelens.entity.User;
import com.codelens.service.ApiKeyService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Authenticates {@code /api/scan/file} (and any future
 * {@code /api/scan/...} endpoint) by an
 * {@code Authorization: Bearer cl_live_xxxx...} header.
 *
 * <p>Flow:</p>
 * <ol>
 *   <li>Skip when the request is not for {@code /api/scan/file}
 *       — JWT or other filters take over.</li>
 *   <li>Parse the {@code Authorization} header.</li>
 *   <li>Extract the prefix ({@code cl_live_} + first 8 hex chars).</li>
 *   <li>Look up the user by prefix.</li>
 *   <li>Bcrypt-verify the full key against the stored hash.</li>
 *   <li>Apply a 60-second Redis rate limit
 *       (key {@code ratelimit:apikey:{userId}}).</li>
 *   <li>On success: set the {@code SecurityContextHolder} with
 *       a {@code ROLE_API_KEY} authority so the controller can
 *       accept the call.</li>
 *   <li>On any failure: 403 (or 429 if rate-limited).</li>
 * </ol>
 *
 * <p>This filter is registered <strong>before</strong> {@link JwtAuthFilter}
 * in the chain; for non-{@code /api/scan/file} requests it returns
 * immediately and the JWT filter handles auth as usual.</p>
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    public static final String AUTH_HEADER = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";
    public static final String SCAN_FILE_PATH = "/api/scan/file";
    public static final String RATE_LIMIT_KEY_PREFIX = "ratelimit:apikey:";

    private final ApiKeyService apiKeyService;
    private final StringRedisTemplate redis;
    private final int requestsPerMinute;
    private final CircuitBreaker redisCircuitBreaker;

    public ApiKeyAuthFilter(ApiKeyService apiKeyService,
                            StringRedisTemplate redis,
                            @Value("${app.ratelimit.api-key.requests-per-minute:60}") int requestsPerMinute,
                            @Qualifier("redisRateLimiterCircuitBreaker") CircuitBreaker redisCircuitBreaker) {
        this.apiKeyService = apiKeyService;
        this.redis = redis;
        this.requestsPerMinute = requestsPerMinute;
        this.redisCircuitBreaker = redisCircuitBreaker;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !SCAN_FILE_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        String authHeader = request.getHeader(AUTH_HEADER);
        // Fall through to the JWT filter if no Bearer token is present.
        // /api/scan/file is dual-auth (JWT cookie OR API key); only
        // short-circuit when the caller is clearly using a key.
        if (authHeader == null) {
            chain.doFilter(request, response);
            return;
        }
        if (!authHeader.startsWith(BEARER_PREFIX)) {
            // Some other auth scheme (e.g. Basic) — let downstream handle it.
            chain.doFilter(request, response);
            return;
        }
        String key = authHeader.substring(BEARER_PREFIX.length()).trim();

        // If the token isn't an API key, let the JWT filter try.
        if (!key.startsWith(ApiKeyService.KEY_PREFIX_LITERAL)) {
            chain.doFilter(request, response);
            return;
        }

        // Extract the lookup prefix (literal + first 8 hex chars)
        int prefixLen = ApiKeyService.KEY_PREFIX_LITERAL.length() + 8;
        if (key.length() < prefixLen) {
            unauthorized(response, "API key too short");
            return;
        }
        String prefix = key.substring(0, prefixLen);

        ApiKey stored = apiKeyService.findByPrefix(prefix);
        if (!apiKeyService.verify(stored, key)) {
            unauthorized(response, "Invalid API key");
            return;
        }

        // Rate limit (per user, sliding 60-second window)
        User user = stored.getUser();
        if (user == null) {
            unauthorized(response, "Key has no associated user");
            return;
        }
        if (!rateLimitAllows(user.getId())) {
            tooManyRequests(response, requestsPerMinute);
            return;
        }

        // Build a principal that the controller can read.
        // The principal is the User (so @AuthenticationPrincipal works
        // the same way as in JWT-authenticated calls).
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority("ROLE_API_KEY")));
        authentication.setDetails(buildDetails(user));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // Stamp the key as used now (best-effort; ignore failures)
        try {
            apiKeyService.markUsed(stored);
        } catch (Exception ex) {
            log.debug("Failed to mark API key {} as used: {}", stored.getId(), ex.getMessage());
        }

        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * INCR-then-EXPIRE pattern with circuit breaker fallback.
     * The first request for a given key in a minute creates the bucket
     * and sets a 60s TTL; subsequent requests in the same window just
     * increment. If the count exceeds the limit we reject.
     *
     * <p>When Redis is down, the circuit breaker opens and falls back to
     * an in-memory rate limiter to prevent complete denial of service.</p>
     */
    private boolean rateLimitAllows(UUID userId) {
        String key = RATE_LIMIT_KEY_PREFIX + userId;

        // Use circuit breaker for Redis operations
        try {
            return CircuitBreaker.decorateSupplier(redisCircuitBreaker,
                () -> checkRateLimitWithRedis(key, userId)).get();
        } catch (Exception e) {
            log.warn("Circuit breaker open for Redis rate limiting, falling back to in-memory limiter for user {}", userId);
            return fallbackRateLimit(userId);
        }
    }

    /**
     * Actual Redis rate limiting implementation
     */
    private boolean checkRateLimitWithRedis(String key, UUID userId) {
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            throw new RuntimeException("Redis operation failed");
        }
        if (count == 1L) {
            redis.expire(key, Duration.ofSeconds(60));
        }
        return count <= requestsPerMinute;
    }

    /**
     * In-memory sliding window rate limiter used as fallback when Redis
     * is unavailable. Tracks timestamps per user in a minute window.
     */
    private static final int FALLBACK_RPM = 10;
    private static final long WINDOW_MS = 60_000L;
    private static final ConcurrentHashMap<UUID, ConcurrentLinkedDeque<Long>> memoryBuckets = new ConcurrentHashMap<>();

    /**
     * Fallback in-memory rate limiter when Redis is unavailable.
     * Uses a sliding window: tracks request timestamps per user and
     * allows N requests per minute window.
     */
    private boolean fallbackRateLimit(UUID userId) {
        long now = System.currentTimeMillis();
        long windowStart = now - WINDOW_MS;

        ConcurrentLinkedDeque<Long> timestamps = memoryBuckets.computeIfAbsent(
                userId, k -> new ConcurrentLinkedDeque<>());

        // Remove stale entries outside the window.
        while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
            timestamps.pollFirst();
        }

        if (timestamps.size() >= FALLBACK_RPM) {
            return false; // Rate limited.
        }

        timestamps.addLast(now);
        return true;
    }

    private void unauthorized(HttpServletResponse res, String message) throws IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setHeader("WWW-Authenticate", "Bearer");
        res.setContentType("application/json");
        res.getWriter().write(
                "{\"error\":\"API_KEY_INVALID\",\"message\":\"" + message + "\"}");
    }

    private void tooManyRequests(HttpServletResponse res, int retryAfter) throws IOException {
        // HTTP 429 (Too Many Requests) is not exposed as a constant on
        // jakarta.servlet.http.HttpServletResponse in Servlet 5/6 (it
        // exists on some legacy javax.servlet containers but not on the
        // Jakarta API). Use the literal RFC 6585 code.
        res.setStatus(429);
        res.setHeader("Retry-After", String.valueOf(retryAfter));
        res.setContentType("application/json");
        res.getWriter().write(
                "{\"error\":\"RATE_LIMITED\",\"message\":\"Too many requests; retry in "
                        + retryAfter + "s\"}");
    }

    /** Wrap the User in a UserDetails so Spring's auth principal chain is happy. */
    private UserDetails buildDetails(User u) {
        return org.springframework.security.core.userdetails.User
                .withUsername(u.getGithubUsername())
                .password("")
                .authorities("ROLE_API_KEY")
                .build();
    }
}
