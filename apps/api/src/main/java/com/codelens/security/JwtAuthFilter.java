package com.codelens.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Reads the {@code accessToken} cookie, validates the JWT, and populates
 * the SecurityContext with a {@link UsernamePasswordAuthenticationToken}
 * whose principal is the user {@link UUID}.
 *
 * <p>Public endpoints (auth flow, webhook, actuator health) are skipped
 * before JWT validation; their permitAll status is enforced by
 * {@link com.codelens.config.SecurityConfig}.</p>
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String ACCESS_TOKEN_COOKIE = "accessToken";

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        String token = readAccessTokenCookie(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Claims claims = jwtService.validateToken(token);
                // Only accept access tokens here; refresh tokens are for /api/auth/refresh.
                if (!JwtService.TYPE_ACCESS.equals(claims.get(JwtService.CLAIM_TYPE))) {
                    chain.doFilter(request, response);
                    return;
                }
                UUID userId = UUID.fromString(claims.getSubject());
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException | IllegalArgumentException ex) {
                // Invalid token: leave context anonymous; SecurityFilterChain will reject.
                SecurityContextHolder.clearContext();
            }
        }

        chain.doFilter(request, response);
    }

    private static String readAccessTokenCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (ACCESS_TOKEN_COOKIE.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
