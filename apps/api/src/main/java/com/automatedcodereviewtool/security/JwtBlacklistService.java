package com.automatedcodereviewtool.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Revokes access tokens on logout.
 *
 * <p>The token's {@code jti} is stored in Redis under
 * {@code blacklist:jti:<jti>} with a TTL equal to the token lifetime, so
 * entries expire on their own once the token could no longer be used
 * anyway.</p>
 *
 * <p><b>Redis-outage policy.</b> Auth paths fail closed: if Redis is
 * unreachable, JWT-authenticated requests (blacklist check), OAuth login,
 * token refresh and logout (refresh-session store) answer 503 rather than
 * accepting a token that might have been revoked. Rate limiters fail open
 * with a warning — the auth limiter lets the request through, the API-key
 * limiter falls back to a per-instance in-memory limit.</p>
 */
@Service
public class JwtBlacklistService {

    private static final String BLACKLIST_PREFIX = "blacklist:jti:";

    private final StringRedisTemplate redis;

    public JwtBlacklistService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @param jti        the unique JWT ID from the token claims
     * @param ttlSeconds how long to remember it (the token's lifetime)
     */
    public void blacklistToken(String jti, long ttlSeconds) {
        if (jti == null || jti.isBlank()) {
            throw new IllegalArgumentException("JWT ID cannot be null or empty");
        }
        redis.opsForValue().set(BLACKLIST_PREFIX + jti, "1", Duration.ofSeconds(ttlSeconds));
    }

    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redis.hasKey(BLACKLIST_PREFIX + jti));
    }
}
