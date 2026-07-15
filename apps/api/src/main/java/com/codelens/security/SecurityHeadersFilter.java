package com.codelens.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Filter that adds security headers to HTTP responses.
 *
 * <p>This filter implements security best practices by adding headers like:</p>
 * <ul>
 *   <li>Content Security Policy (CSP)</li>
 *   <li>Strict Transport Security (HSTS)</li>
 *   <li>X-Content-Type-Options</li>
 *   <li>X-Frame-Options</li>
 *   <li>Referrer Policy</li>
 *   <li>Permissions Policy</li>
 * </ul>
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                  HttpServletResponse response,
                                  FilterChain filterChain)
            throws ServletException, IOException {

        jakarta.servlet.http.HttpServletResponseWrapper wrappedResponse = new jakarta.servlet.http.HttpServletResponseWrapper(response) {
            @Override
            public void setHeader(String name, String value) {
                if (!"Server".equalsIgnoreCase(name)) {
                    super.setHeader(name, value);
                }
            }

            @Override
            public void addHeader(String name, String value) {
                if (!"Server".equalsIgnoreCase(name)) {
                    super.addHeader(name, value);
                }
            }
        };

        // Add security headers
        addSecurityHeaders(wrappedResponse);

        // Continue the filter chain
        filterChain.doFilter(request, wrappedResponse);
    }

    private void addSecurityHeaders(HttpServletResponse response) {
        // Content Security Policy
        response.setHeader("Content-Security-Policy",
            "default-src 'self'; " +
            "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://cdn.jsdelivr.net/npm/chart.js; " +
            "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; " +
            "img-src 'self' data: https:; " +
            "font-src 'self' https://fonts.gstatic.com; " +
            "connect-src 'self' https://api.github.com ws://localhost:*; " +
            "frame-ancestors 'none'; " +
            "object-src 'none'; " +
            "base-uri 'self'; " +
            "form-action 'self'; " +
            "frame-src 'none'; " +
            "worker-src 'self'; " +
            "manifest-src 'self'; " +
            "upgrade-insecure-requests;");

        // Strict Transport Security
        response.setHeader("Strict-Transport-Security",
            "max-age=31536000; " +
            "includeSubDomains; " +
            "preload");

        // Prevent MIME type sniffing
        response.setHeader("X-Content-Type-Options", "nosniff");

        // Prevent clickjacking
        response.setHeader("X-Frame-Options", "DENY");

        // Cross-site scripting protection
        response.setHeader("X-XSS-Protection", "1; mode=block");

        // Referrer control
        response.setHeader("Referrer-Policy",
            "strict-origin-when-cross-origin");

        // Permissions Policy
        response.setHeader("Permissions-Policy",
            "accelerometer=(), " +
            "ambient-light-sensor=(), " +
            "autoplay=(), " +
            "battery=(), " +
            "bluetooth=(), " +
            "browsing-topics=(), " +
            "camera=(), " +
            "cross-origin-isolated=(), " +
            "document-domain=(), " +
            "encrypted-media=(), " +
            "execution-while-not-rendered=(), " +
            "execution-while-out-of-viewport=(), " +
            "fullscreen=(self), " +
            "geolocation=(), " +
            "gyroscope=(), " +
            "hid=(), " +
            "identity-credentials-get=(), " +
            "idle-detection=(), " +
            "local-fonts=(), " +
            "magnetometer=(), " +
            "microphone=(), " +
            "midi=(), " +
            "navigation-override=(), " +
            "payment=(), " +
            "picture-in-picture=(), " +
            "publickey-credentials-get=(), " +
            "screen-wake-lock=(), " +
            "serial=(), " +
            "storage-access=(), " +
            "usb=(), " +
            "web-share=(), " +
            "window-management=(), " +
            "xr-spatial-tracking=()");

        // Remove server header
        response.setHeader("Server", "");

        // Cache control for sensitive responses
        if (response.getContentType() != null &&
            (response.getContentType().contains("json") ||
             response.getContentType().contains("text"))) {
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, private");
            response.setHeader("Pragma", "no-cache");
            response.setDateHeader("Expires", 0);
        }
    }
}