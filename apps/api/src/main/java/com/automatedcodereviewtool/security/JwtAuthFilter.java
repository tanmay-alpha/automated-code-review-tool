package com.automatedcodereviewtool.security;

import com.automatedcodereviewtool.logging.SecurityEventLogger;
import com.automatedcodereviewtool.security.JwtBlacklistService;
import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.repository.UserRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String ACCESS_TOKEN_COOKIE = "accessToken";

    private final JwtService jwtService;
    private final JwtBlacklistService jwtBlacklistService;
    private final UserRepository userRepository;

    @Autowired
    private SecurityEventLogger securityEventLogger;

    public JwtAuthFilter(JwtService jwtService,
                         JwtBlacklistService jwtBlacklistService,
                         UserRepository userRepository) {
        this.jwtService = jwtService;
        this.jwtBlacklistService = jwtBlacklistService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        if ("POST".equals(request.getMethod()) && "/api/auth/logout".equals(request.getRequestURI())) {
            String csrfHeader = request.getHeader("X-CSRF-Token");
            if (csrfHeader == null || csrfHeader.isBlank()) {
                securityEventLogger.logSecurityViolation("CSRF_PROTECTION", "Missing CSRF token", "unknown");
            }
        }

        String token = readAccessTokenCookie(request);
        if (token == null) {
            String bearerToken = request.getHeader("Authorization");
            if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
                token = bearerToken.substring(7);
            }
        }
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Claims claims = jwtService.validateToken(token);
                // Only accept access tokens here; refresh tokens are for /api/auth/refresh.
                if (!JwtService.TYPE_ACCESS.equals(claims.get(JwtService.CLAIM_TYPE))) {
                    chain.doFilter(request, response);
                    return;
                }

                // Check if token is blacklisted
                String jti = claims.get(JwtService.CLAIM_JTI, String.class);
                if (jwtBlacklistService.isBlacklisted(jti)) {
                    // Token is blacklisted - clear context and reject
                    SecurityContextHolder.clearContext();
                    chain.doFilter(request, response);
                    return;
                }

                UUID userId = UUID.fromString(claims.getSubject());

                // Look up the full User entity so controllers using
                // @AuthenticationPrincipal User receive a fully-loaded
                // entity, not a raw UUID (the same shape ApiKeyAuthFilter
                // uses). If the user vanished (account deleted, DB reset),
                // fall through as anonymous — SecurityConfig will reject.
                User user = userRepository.findById(userId).orElse(null);
                if (user == null) {
                    SecurityContextHolder.clearContext();
                } else {
                    // Pass the JPA User as principal so controllers can use
                    // @AuthenticationPrincipal User directly. Use the
                    // github username for the UserDetails carried in
                    // setDetails() — defensive wrapper for any controller
                    // that opts into @AuthenticationPrincipal UserDetails.
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            user, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                    auth.setDetails(buildDetails(user));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
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

    /** Wrap the User in a UserDetails so controllers can do
     *  {@code @AuthenticationPrincipal UserDetails caller}.getUsername()
     *  to check ownership against {@link User#getGithubUsername()}. */
    private static org.springframework.security.core.userdetails.UserDetails buildDetails(User u) {
        return org.springframework.security.core.userdetails.User
                .withUsername(u.getGithubUsername())
                .password("")
                .authorities("ROLE_USER")
                .build();
    }
}
