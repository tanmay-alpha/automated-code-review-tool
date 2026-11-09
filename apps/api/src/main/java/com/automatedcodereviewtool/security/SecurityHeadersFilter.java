package com.automatedcodereviewtool.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds security headers to every response and suppresses the
 * {@code Server} header. The API only serves JSON, so the CSP allows no
 * content at all (ASVS 5.0 V3.4.3 asks for {@code object-src 'none'} and
 * {@code base-uri 'none'}) and forbids framing (V3.4.6).
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
        response.setHeader("Content-Security-Policy",
            "default-src 'none'; frame-ancestors 'none'; object-src 'none'; base-uri 'none'");

        // Strict Transport Security
        response.setHeader("Strict-Transport-Security",
            "max-age=31536000; " +
            "includeSubDomains; " +
            "preload");

        // Prevent MIME type sniffing
        response.setHeader("X-Content-Type-Options", "nosniff");

        // Prevent clickjacking
        response.setHeader("X-Frame-Options", "DENY");

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

        response.setHeader("Server", "");
    }
}