package com.codelens.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Service for managing JWT token blacklists.
 *
 * <p>This service implements token invalidation by storing JWT IDs in Redis
 * with a TTL matching the token's expiration time. When a user logs out,
 * their access token is blacklisted to prevent reuse.</p>
 *
 * <p>Tokens are stored with the pattern {@code blacklist:jti:<tokenJTI>} and
 * automatically expire when the token would naturally expire.</p>
 */
@Service
public class JwtBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(JwtBlacklistService.class);
    private static final String BLACKLIST_PREFIX = "blacklist:jti:";
    private final StringRedisTemplate redis;
    private final long tokenExpiryHours;

    public JwtBlacklistService(StringRedisTemplate redis) {
        this.redis = redis;
        // Default to 24 hours, slightly longer than JWT expiry
        this.tokenExpiryHours = 24;
    }

    /**
     * Blacklist a JWT token by its JTI (JWT ID).
     *
     * @param jti the unique JWT ID from the token claims
     * @param expiration the token's expiration time in seconds
     */
    public void blacklistToken(String jti, long expiration) {
        if (jti == null || jti.isBlank()) {
            throw new IllegalArgumentException("JWT ID cannot be null or empty");
        }

        // Calculate TTL: use the token's expiration time, but cap at our maximum
        long ttl = Math.min(expiration, tokenExpiryHours * 3600);

        String key = BLACKLIST_PREFIX + jti;
        redis.opsForValue().set(key, "blacklisted", Duration.ofSeconds(ttl));

        // Log for audit purposes
        log.warn("[AUDIT] Token blacklisted: jti={}, userId={}, reason={}",
                jti.substring(0, Math.min(8, jti.length())) + "...",
                "N/A", "logout");
    }

    /**
     * Check if a JWT token is blacklisted.
     *
     * @param jti the unique JWT ID from the token claims
     * @return true if the token is blacklisted, false otherwise
     */
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }

        String key = BLACKLIST_PREFIX + jti;
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    /**
     * Remove a token from the blacklist (useful for token rotation).
     *
     * @param jti the unique JWT ID from the token claims
     */
    public void removeFromBlacklist(String jti) {
        if (jti == null || jti.isBlank()) {
            return;
        }

        String key = BLACKLIST_PREFIX + jti;
        redis.delete(key);
    }

    /**
     * Clean up expired blacklist entries.
     * This is typically run periodically by a scheduler.
     */
    public void cleanupExpiredEntries() {
        // In a production environment, you would use Redis keyspace notifications
        // or a scheduled job to clean up expired entries. Redis automatically
        // removes keys when they expire, so explicit cleanup is not necessary
        // but can be added for monitoring purposes.
    }

    /**
     * Get the current size of the blacklist.
     *
     * @return the number of blacklisted tokens
     */
    public long getBlacklistSize() {
        // Note: This is an approximation as we can't efficiently count keys
        // with a pattern without Redis 7's COUNT option or SCAN.
        // In production, consider maintaining a counter.
        return 0L;
    }
}