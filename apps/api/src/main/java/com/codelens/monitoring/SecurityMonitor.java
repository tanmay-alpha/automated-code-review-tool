package com.codelens.monitoring;

import com.codelens.logging.SecurityEventLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Comprehensive security monitoring service.
 *
 * <p>This service tracks security metrics and provides health checks for:
 * <ul>
 *   <li>Failed authentication attempts</li>
 *   <li>Rate limiting events</li>
 *   <li>Suspicious IP addresses</li>
 *   <li>System security posture</li>
 * </ul>
 */
@Component
public class SecurityMonitor implements HealthIndicator {

    private final SecurityEventLogger eventLogger;

    // Track failed attempts by IP
    private final ConcurrentHashMap<String, AtomicInteger> failedAttemptsByIP = new ConcurrentHashMap<>();

    // Track rate limiting events
    private final AtomicInteger rateLimitEvents = new AtomicInteger(0);

    // Track security violations
    private final AtomicInteger securityViolations = new AtomicInteger(0);

    // Track successful authentications
    private final AtomicInteger successfulAuths = new AtomicInteger(0);

    // Track blacklisted tokens
    private final AtomicInteger blacklistedTokens = new AtomicInteger(0);

    @Autowired
    public SecurityMonitor(SecurityEventLogger eventLogger) {
        this.eventLogger = eventLogger;
    }

    /**
     * Record a failed authentication attempt.
     */
    public void recordFailedAttempt(String ip, String reason) {
        failedAttemptsByIP.computeIfAbsent(ip, k -> new AtomicInteger(0)).incrementAndGet();
        eventLogger.logAuthFailure(reason, null, ip);

        // Check for brute force pattern
        AtomicInteger attempts = failedAttemptsByIP.get(ip);
        if (attempts != null && attempts.get() >= 5) {
            eventLogger.logBruteForceAttempt(ip, attempts.get(), 60);
        }
    }

    /**
     * Record a successful authentication.
     */
    public void recordSuccess(UUID userId, String username, String method) {
        successfulAuths.incrementAndGet();
        eventLogger.logAuthenticationSuccess(userId, username, method);

        // Reset failed attempts on successful auth
        failedAttemptsByIP.clear();
    }

    /**
     * Record a rate limiting event.
     */
    public void recordRateLimitEvent(Object userId, String ip, String endpoint, String reason) {
        rateLimitEvents.incrementAndGet();
        eventLogger.logRateLimitEvent(userId, ip, endpoint, reason);
    }

    /**
     * Record a security violation.
     */
    public void recordSecurityViolation(String violationType, String details, String ip) {
        securityViolations.incrementAndGet();
        eventLogger.logSecurityViolation(violationType, details, ip);
    }

    /**
     * Record a token blacklisting event.
     */
    public void recordTokenBlacklisting(String jti, UUID userId, String reason) {
        blacklistedTokens.incrementAndGet();
        eventLogger.logTokenBlacklisting(jti, userId, reason);
    }

    /**
     * Get security metrics.
     */
    public SecurityMetrics getMetrics() {
        SecurityMetrics metrics = new SecurityMetrics();
        metrics.setFailedAttempts(failedAttemptsByIP.size());
        metrics.setRateLimitEvents(rateLimitEvents.get());
        metrics.setSecurityViolations(securityViolations.get());
        metrics.setSuccessfulAuths(successfulAuths.get());
        metrics.setBlacklistedTokens(blacklistedTokens.get());
        metrics.setLastUpdated(Instant.now());
        return metrics;
    }

    /**
     * Reset metrics (typically called daily).
     */
    @Scheduled(cron = "0 0 0 * * ?") // Daily at midnight
    public void resetMetrics() {
        failedAttemptsByIP.clear();
        rateLimitEvents.set(0);
        securityViolations.set(0);
        successfulAuths.set(0);
        blacklistedTokens.set(0);
    }

    @Override
    public Health health() {
        SecurityMetrics metrics = getMetrics();

        // Check if we have too many failed attempts
        if (metrics.getFailedAttempts() > 10) {
            return Health.down()
                    .withDetail("securityStatus", "HIGH_RISK")
                    .withDetail("failedAttempts", metrics.getFailedAttempts())
                    .build();
        }

        // Check if we have too many security violations
        if (metrics.getSecurityViolations() > 5) {
            return Health.warning()
                    .withDetail("securityStatus", "MEDIUM_RISK")
                    .withDetail("securityViolations", metrics.getSecurityViolations())
                    .build();
        }

        return Health.up()
                .withDetail("securityStatus", "NORMAL")
                .withDetail("failedAttempts", metrics.getFailedAttempts())
                .withDetail("rateLimitEvents", metrics.getRateLimitEvents())
                .withDetail("securityViolations", metrics.getSecurityViolations())
                .withDetail("successfulAuths", metrics.getSuccessfulAuths())
                .withDetail("blacklistedTokens", metrics.getBlacklistedTokens())
                .build();
    }

    /**
     * Metrics data structure.
     */
    public static class SecurityMetrics {
        private int failedAttempts;
        private int rateLimitEvents;
        private int securityViolations;
        private int successfulAuths;
        private int blacklistedTokens;
        private Instant lastUpdated;

        // Getters and setters
        public int getFailedAttempts() { return failedAttempts; }
        public void setFailedAttempts(int failedAttempts) { this.failedAttempts = failedAttempts; }

        public int getRateLimitEvents() { return rateLimitEvents; }
        public void setRateLimitEvents(int rateLimitEvents) { this.rateLimitEvents = rateLimitEvents; }

        public int getSecurityViolations() { return securityViolations; }
        public void setSecurityViolations(int securityViolations) { this.securityViolations = securityViolations; }

        public int getSuccessfulAuths() { return successfulAuths; }
        public void setSuccessfulAuths(int successfulAuths) { this.successfulAuths = successfulAuths; }

        public int getBlacklistedTokens() { return blacklistedTokens; }
        public void setBlacklistedTokens(int blacklistedTokens) { this.blacklistedTokens = blacklistedTokens; }

        public Instant getLastUpdated() { return lastUpdated; }
        public void setLastUpdated(Instant lastUpdated) { this.lastUpdated = lastUpdated; }
    }
}