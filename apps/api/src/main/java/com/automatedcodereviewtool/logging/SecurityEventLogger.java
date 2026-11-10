package com.automatedcodereviewtool.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes security events (rate limiting, rejected webhooks, oversized
 * requests, rejected cross-origin requests, logout and session revocation)
 * to the {@code SECURITY} logger,
 * which logback-spring.xml routes to {@code logs/security-audit.log}.
 */
@Component
public class SecurityEventLogger {

    private static final Logger SECURITY_LOGGER = LoggerFactory.getLogger("SECURITY");

    /**
     * Logs access-token revocation on logout.
     *
     * @param jti    the JWT ID being blacklisted
     * @param userId the user ID
     * @param reason why the token was revoked
     */
    public void logTokenBlacklisting(String jti, UUID userId, String reason) {
        Map<String, Object> event = createEvent("TOKEN_BLACKLIST");
        event.put("jti", jti);
        event.put("userId", userId);
        event.put("reason", reason);
        event.put("timestamp", Instant.now());

        SECURITY_LOGGER.info("Token blacklisted: {}", formatEvent(event));
    }

    /**
     * Logs revocation of every session of a user ("sign out everywhere").
     *
     * @param userId the user ID
     * @param reason why the sessions were revoked
     */
    public void logSessionRevocation(UUID userId, String reason) {
        Map<String, Object> event = createEvent("SESSION_REVOCATION");
        event.put("userId", userId);
        event.put("reason", reason);
        event.put("timestamp", Instant.now());

        SECURITY_LOGGER.info("All sessions revoked: {}", formatEvent(event));
    }

    /**
     * Logs API rate limiting events.
     *
     * @param userId   the user ID, or {@code null} for per-IP limits
     * @param ip       the client IP
     * @param endpoint the endpoint being accessed
     * @param reason   the rate limit reason
     */
    public void logRateLimitEvent(Object userId, String ip, String endpoint, String reason) {
        Map<String, Object> event = createEvent("RATE_LIMIT");
        event.put("userId", userId);
        event.put("ip", ip);
        event.put("endpoint", endpoint);
        event.put("reason", reason);
        event.put("timestamp", Instant.now());

        SECURITY_LOGGER.warn("Rate limit triggered: {}", formatEvent(event));
    }

    /**
     * Logs webhook signature verification results.
     *
     * @param repoId         the GitHub repository ID
     * @param signatureValid whether the HMAC signature matched
     * @param ip             the source IP
     * @param action         the GitHub webhook action
     */
    public void logWebhookSecurityEvent(String repoId, boolean signatureValid, String ip, String action) {
        Map<String, Object> event = createEvent("WEBHOOK_SECURITY");
        event.put("repoId", repoId);
        event.put("signatureValid", signatureValid);
        event.put("ip", ip);
        event.put("action", action);
        event.put("timestamp", Instant.now());

        if (signatureValid) {
            SECURITY_LOGGER.info("Valid webhook received: {}", formatEvent(event));
        } else {
            SECURITY_LOGGER.error("Invalid webhook signature detected: {}", formatEvent(event));
        }
    }

    /**
     * Logs a rejected request (e.g. oversized query/header/body).
     *
     * @param violationType short machine-readable type
     * @param details       human-readable details
     * @param ip            the source IP
     */
    public void logSecurityViolation(String violationType, String details, String ip) {
        Map<String, Object> event = createEvent("SECURITY_VIOLATION");
        event.put("violationType", violationType);
        event.put("details", details);
        event.put("ip", ip);
        event.put("timestamp", Instant.now());

        SECURITY_LOGGER.error("Security violation detected - {}: {}", violationType, formatEvent(event));
    }

    /**
     * Creates a base event structure with request context.
     */
    private Map<String, Object> createEvent(String eventType) {
        Map<String, Object> event = new HashMap<>();
        event.put("eventType", eventType);
        event.put("requestId", UUID.randomUUID().toString());

        // Add request context if available
        try {
            HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
            event.put("path", request.getRequestURI());
            event.put("method", request.getMethod());
            event.put("remoteAddr", request.getRemoteAddr());

            // User agent
            String userAgent = request.getHeader("User-Agent");
            if (userAgent != null) {
                // Redact sensitive parts of user agent
                userAgent = userAgent.replaceAll("(?i)(password|pwd|token)=[^;\\s]*", "[REDACTED]");
                event.put("userAgent", userAgent);
            }
        } catch (Exception e) {
            // No request context available
            event.put("path", "unknown");
        }

        return event;
    }

    /**
     * Formats the event for logging.
     */
    private String formatEvent(Map<String, Object> event) {
        StringBuilder sb = new StringBuilder();
        sb.append("[ ").append(event.get("eventType")).append(" ]");

        // Add key-value pairs
        event.forEach((key, value) -> {
            if (!key.equals("eventType")) {
                sb.append(" ").append(key).append("=").append(value);
            }
        });

        return sb.toString();
    }
}