package com.codelens.security;
import com.codelens.logging.SecurityEventLogger;
import com.codelens.monitoring.SecurityMonitor;
import org.springframework.beans.factory.annotation.Autowired;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Filter that enforces request size limits.
 *
 * <p>This filter:</p>
 * <ul>
 *   <li>Limits the size of request bodies</li>
 *   <li>Limits the size of query parameters</li>
 *   <li>Limits the size of individual headers</li>
 *   <li>Logs oversized requests</li>
 * </ul>
 */
@Component
public class RequestSizeLimitFilter extends org.springframework.web.filter.OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    jakarta.servlet.http.HttpServletResponse response,
                                    jakarta.servlet.FilterChain filterChain)
            throws jakarta.servlet.ServletException, IOException {
        try {
            LimitedContentCachingRequestWrapper wrapped = new LimitedContentCachingRequestWrapper(request);
            filterChain.doFilter(wrapped, response);
        } catch (SecurityException ex) {
            response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"REQUEST_LIMIT_EXCEEDED\",\"message\":\"" + ex.getMessage() + "\"}");
        }
    }

    @Autowired
    private SecurityEventLogger securityLogger;

    @Autowired
    private SecurityMonitor securityMonitor;

    @Value("${app.request.max-body-size:10485760}") // 10MB default
    private long maxBodySize;

    @Value("${app.request.max-query-size:4096}") // 4KB default
    private int maxQuerySize;

    @Value("${app.request.max-header-size:4096}") // 4KB default
    private int maxHeaderSize;

    /**
     * Wraps the request to enforce size limits.
     */
    public HttpServletRequest wrapRequest(HttpServletRequest request) {
        return new LimitedContentCachingRequestWrapper(request);
    }

    /**
     * Request wrapper that caches content and enforces size limits.
     */
    private class LimitedContentCachingRequestWrapper extends ContentCachingRequestWrapper {

        public LimitedContentCachingRequestWrapper(HttpServletRequest request) {
            super(request);
            checkRequestLimits(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            // Check body size before reading
            if (getContentLength() > maxBodySize) {
                throw new IOException("Request body size exceeds limit of " + maxBodySize + " bytes");
            }
            return super.getInputStream();
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (getContentLength() > maxBodySize) {
                throw new IOException("Request body size exceeds limit of " + maxBodySize + " bytes");
            }
            return super.getReader();
        }

        private void checkRequestLimits(HttpServletRequest request) {
            String ip = request.getRemoteAddr();
            if (ip == null || ip.isBlank()) {
                ip = "unknown";
            }

            // Check query string size
            String queryString = request.getQueryString();
            if (queryString != null && queryString.getBytes(StandardCharsets.UTF_8).length > maxQuerySize) {
                securityLogger.logSecurityViolation(
                    "QUERY_STRING_TOO_LONG",
                    "Query string exceeds " + maxQuerySize + " bytes",
                    ip
                );
                securityMonitor.recordSecurityViolation(
                    "QUERY_STRING_TOO_LONG",
                    "Query string exceeds " + maxQuerySize + " bytes",
                    ip
                );
                throw new SecurityException("Query string too long");
            }

            // Check individual header sizes
            final String finalIp = ip;
            request.getHeaderNames().asIterator().forEachRemaining(headerName -> {
                String headerValue = request.getHeader(headerName);
                if (headerValue != null && headerValue.getBytes(StandardCharsets.UTF_8).length > maxHeaderSize) {
                    securityLogger.logSecurityViolation(
                        "HEADER_TOO_LARGE",
                        "Header '" + headerName + "' exceeds limit of " + maxHeaderSize + " bytes",
                        finalIp
                    );
                    securityMonitor.recordSecurityViolation(
                        "HEADER_TOO_LARGE",
                        "Header '" + headerName + "' exceeds limit of " + maxHeaderSize + " bytes",
                        finalIp
                    );
                    throw new SecurityException("Header '" + headerName + "' too large");
                }
            });

            // Check total content length
            int contentLength = request.getContentLength();
            if (contentLength > maxBodySize) {
                securityLogger.logSecurityViolation(
                    "REQUEST_TOO_LARGE",
                    "Request body exceeds limit of " + maxBodySize + " bytes",
                    ip
                );
                securityMonitor.recordSecurityViolation(
                    "REQUEST_TOO_LARGE",
                    "Request body exceeds limit of " + maxBodySize + " bytes",
                    ip
                );
                throw new SecurityException("Request body too large");
            }
        }
    }
}