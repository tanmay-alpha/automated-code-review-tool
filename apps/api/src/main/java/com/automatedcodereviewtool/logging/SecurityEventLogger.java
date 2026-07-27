package com.codelens.logging;

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
 * Logger for security-related events and authentication failures.
 *
 * <p>This component logs security events to a dedicated audit log file
 * for monitoring and compliance purposes.</p>
 *
 * <p>Log levels:</p>
 * <ul>
 *   <li>INFO - Successful authentication events</li>
 *   <li>WARN - Suspicious activities (multiple failed attempts)</li>
 *   <li>ERROR - Authentication failures and security violations</li>
 * </ul>
 */
@Component
public class SecurityEventLogger {

    private static final Logger SECURITY_LOGGER = LoggerFactory.getLogger("SECURITY");
    private static final Logger log = LoggerFactory.getLogger(SecurityEventLogger.class);

    /**
     * Logs a successful authentication event.
     *
     * @param userId The authenticated user ID
     * @param username The username/GitHub login
     * @param method The authentication method (jwt, api-key, github)
     */
    public void logAuthenticationSuccess(UUID userId, String username, String method) {
        Map<String, Object> event = createEvent("AUTH_SUCCESS");
        event.put("userId", userId);
        event.put("username", username);
        event.put("method", method);
        event.put("timestamp", Instant.now());

        SECURITY_LOGGER.info("Authentication successful: {}", formatEvent(event));
    }

    /**
     * Logs a JWT authentication failure.
     *
     * @param reason The failure reason (expired, invalid, missing)
     * @param userAgent The user agent string
     * @param ip The client IP address
     */
    public void logAuthFailure(String reason, String userAgent, String ip) {
        Map<String, Object> event = createEvent("AUTH_FAILURE");
        event.put("reason", reason);
        event.put("userAgent", userAgent);
        event.put("ip", ip);
        event.put("timestamp", Instant.now());

        SECURITY_LOGGER.warn("Authentication failed - {}: {}", reason, formatEvent(event));
    }

    /**
     * Logs multiple failed authentication attempts from the same IP.
     *
     * @param ip The source IP address
     * @param count The number of failed attempts
     * @param timeframe The timeframe of the attempts (seconds)
     */
    public void logBruteForceAttempt(String ip, int count, int timeframe) {
        Map<String, Object> event = createEvent("BRUTE_FORCE_ATTEMPT");
        event.put("ip", ip);
        event.put("attemptCount", count);
        event.put("timeframe", timeframe);
        event.put("timestamp", Instant.now());

        SECURITY_LOGGER.warn("Potential brute force attack detected: {}", formatEvent(event));
    }

    /**
     * Logs JWT blacklisting events.
     *
     * @param jti The JWT ID being blacklisted
     * @param userId The user ID
     * @param reason The reason for blacklisting (logout, token revoke)
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
     * Logs API rate limiting events.
     *
     * @param userId The user ID (or API key)
     * @param ip The client IP
     * @param endpoint The endpoint being accessed
     * @param reason The rate limit reason (too many requests, circuit breaker open)
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
     * Logs webhook security events.
     *
     * @param repoId The repository ID
     * @param signatureStatus Whether the HMAC signature was valid
     * @param ip The source IP
     * @param action The GitHub webhook action
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
     * Logs security policy violations.
     *
     * @param violationType The type of violation (CSP, CORS, etc.)
     * @param details Details about the violation
     * @param ip The source IP
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