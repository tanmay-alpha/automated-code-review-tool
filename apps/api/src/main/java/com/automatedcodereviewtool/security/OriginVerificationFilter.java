package com.automatedcodereviewtool.security;

import com.automatedcodereviewtool.config.AppConfig;
import com.automatedcodereviewtool.logging.SecurityEventLogger;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * CSRF defence for cookie-authenticated requests (OWASP ASVS 5.0 V3.5.1 /
 * WSTG-SESS-05): an unsafe request ({@code POST}, {@code PUT}, {@code PATCH},
 * {@code DELETE}) that carries a session cookie must prove it was initiated
 * by an allowed frontend origin.
 *
 * <ul>
 *   <li>{@code Origin} present: it must equal one of
 *       {@link AppConfig#getAllowedOrigins()} exactly (scheme, host and port).
 *       The opaque origin {@code null} never matches.</li>
 *   <li>{@code Origin} absent: {@code Sec-Fetch-Site: same-origin} is
 *       accepted; when no {@code Sec-Fetch-Site} is sent either, the
 *       {@code Referer}'s origin must be allowed.</li>
 *   <li>Anything else: 403 {@code CSRF_ORIGIN_REJECTED}.</li>
 * </ul>
 *
 * <p>Exempt: requests without a session cookie (nothing ambient to abuse),
 * requests with an {@code Authorization: Bearer} header (JWT or API key —
 * a browser cannot attach that header cross-origin without a CORS preflight
 * the API refuses), and the HMAC-verified GitHub webhook. Runs before the
 * CORS filter so the decision does not depend on the CORS configuration.</p>
 */
@Component
public class OriginVerificationFilter extends OncePerRequestFilter {

    public static final String ERROR_CODE = "CSRF_ORIGIN_REJECTED";
    static final String WEBHOOK_PATH = "/api/webhook/github";

    private static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final List<String> allowedOrigins;
    private final AuthCookies authCookies;
    private final SecurityEventLogger securityEventLogger;

    public OriginVerificationFilter(AppConfig appConfig,
                                    SecurityEventLogger securityEventLogger) {
        this.allowedOrigins = appConfig.getAllowedOrigins();
        this.authCookies = new AuthCookies(appConfig);
        this.securityEventLogger = securityEventLogger;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        if (!UNSAFE_METHODS.contains(request.getMethod())) {
            return true;
        }
        if (WEBHOOK_PATH.equals(request.getRequestURI())) {
            return true;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return true;
        }
        return !authCookies.hasSessionCookie(request);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {
        String rejection = rejectionReason(request);
        if (rejection == null) {
            chain.doFilter(request, response);
            return;
        }
        String ip = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
        securityEventLogger.logSecurityViolation(ERROR_CODE,
                rejection + " on " + request.getMethod() + " " + request.getRequestURI(), ip);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + ERROR_CODE + "\","
                + "\"message\":\"Request origin is not allowed for cookie-authenticated requests\","
                + "\"status\":403,\"timestamp\":\"" + Instant.now() + "\"}");
    }

    /** {@code null} when the request may proceed, else a log-safe reason. */
    private String rejectionReason(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null) {
            return isAllowed(origin) ? null : "origin not allowed";
        }
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (fetchSite != null) {
            return "same-origin".equalsIgnoreCase(fetchSite.trim())
                    ? null : "no Origin and Sec-Fetch-Site is not same-origin";
        }
        String referer = request.getHeader("Referer");
        if (referer != null) {
            return isAllowed(referer) ? null : "referer origin not allowed";
        }
        return "no Origin, Sec-Fetch-Site or Referer";
    }

    private boolean isAllowed(String headerValue) {
        String origin = AppConfig.normalizeOrigin(headerValue, false);
        return origin != null && allowedOrigins.contains(origin);
    }
}
