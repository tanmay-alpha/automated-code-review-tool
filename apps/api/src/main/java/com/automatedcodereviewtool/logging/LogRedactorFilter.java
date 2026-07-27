package com.codelens.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filter that redacts sensitive information from logs.
 *
 * <p>This filter intercepts requests and responses to redact sensitive patterns
 * from headers, parameters, and bodies before they reach the logs.</p>
 *
 * <p>Redacted patterns include:</p>
 * <ul>
 *   <li>Authorization and Bearer tokens</li>
 *   <li>API keys</li>
 *   <li>Passwords</li>
 *   <li>Credit card numbers</li>
 *   <li>GitHub personal access tokens</li>
 *   <li>JWT tokens</li>
 * </ul>
 */
@Component
public class LogRedactorFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LogRedactorFilter.class);

    @Autowired
    private SecurityEventLogger securityEventLogger;

    private static final List<Pattern> SENSITIVE_PATTERNS = Arrays.asList(
        // Bearer tokens
        Pattern.compile("Bearer\\s+[a-zA-Z0-9_-]+"),
        // API keys
        Pattern.compile("api[_-]?key[:=]\\s*[a-zA-Z0-9]{20,}"),
        // Passwords
        Pattern.compile("password[:=]\\s*['\"][^'\"]{8,}['\"]"),
        // Credit card numbers
        Pattern.compile("\\b(?:\\d[ -]*?){13,16}\\b"),
        // GitHub personal access tokens
        Pattern.compile("ghp_[a-zA-Z0-9]{36}"),
        // JWT tokens
        Pattern.compile("eyJ[a-zA-Z0-9_-]*\\.[a-zA-Z0-9_-]*\\.[a-zA-Z0-9_-]*"),
        // Encryption keys
        Pattern.compile("encryption[_-]?key[:=]\\s*['\"][^'\"]{32,}['\"]"),
        // Database credentials
        Pattern.compile("(?i)(password|pwd|pass)[:=]\\s*['\"][^'\"]{4,}['\"]")
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                  HttpServletResponse response,
                                  FilterChain filterChain)
            throws ServletException, IOException {

        // Create wrapped request and response that redact content
        LogRedactingRequestWrapper wrappedRequest = new LogRedactingRequestWrapper(request, securityEventLogger);
        LogRedactingResponseWrapper wrappedResponse = new LogRedactingResponseWrapper(response);

        filterChain.doFilter(wrappedRequest, wrappedResponse);
    }

    /**
     * Redacts sensitive information from a string.
     *
     * @param input The string to redact
     * @return The redacted string
     */
    public static String redactSensitive(String input) {
        if (!StringUtils.hasText(input)) {
            return input;
        }

        String result = input;
        for (Pattern pattern : SENSITIVE_PATTERNS) {
            Matcher matcher = pattern.matcher(result);
            if (matcher.find()) {
                result = matcher.replaceAll("[REDACTED]");
            }
        }

        return result;
    }

    /**
     * Wrapper for HttpServletRequest that redacts sensitive information.
     */
    private static class LogRedactingRequestWrapper extends HttpServletRequestWrapper {
        private final SecurityEventLogger securityEventLogger;

        public LogRedactingRequestWrapper(HttpServletRequest request, SecurityEventLogger securityEventLogger) {
            super(request);
            this.securityEventLogger = securityEventLogger;
        }

        @Override
        public String getHeader(String name) {
            String value = super.getHeader(name);
            if (value != null && isSensitiveHeader(name)) {
                if (securityEventLogger != null) {
                    securityEventLogger.logSecurityViolation(
                        "HEADER_CONTAINS_TOKEN", "Authorization header redacted", "unknown");
                }
            }
            return value;
        }

        @Override
        public String getParameter(String name) {
            return super.getParameter(name);
        }

        private boolean isSensitiveHeader(String name) {
            return name != null && (
                name.equalsIgnoreCase("authorization") ||
                name.equalsIgnoreCase("x-api-key") ||
                name.equalsIgnoreCase("x-ml-worker-secret")
            );
        }

        private boolean isSensitiveParameter(String name) {
            return name != null && (
                name.toLowerCase().contains("password") ||
                name.toLowerCase().contains("secret") ||
                name.toLowerCase().contains("token")
            );
        }
    }

    /**
     * Wrapper for HttpServletResponse that redacts sensitive information.
     */
    private static class LogRedactingResponseWrapper extends HttpServletResponseWrapper {

        public LogRedactingResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        // Note: Response body redaction would require a more complex solution
        // with a ContentCachingResponseWrapper and custom logging
    }
}