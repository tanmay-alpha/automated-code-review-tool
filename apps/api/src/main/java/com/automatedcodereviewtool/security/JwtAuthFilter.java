package com.automatedcodereviewtool.security;

import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Authenticates dashboard requests from the {@code accessToken} cookie
 * (or an {@code Authorization: Bearer <jwt>} header). Only access tokens
 * that are not blacklisted (logged out) and whose user still exists are
 * accepted; anything else leaves the request anonymous. If the blacklist
 * cannot be checked (Redis down) the request gets 503.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String ACCESS_TOKEN_COOKIE = "accessToken";

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final JwtService jwtService;
    private final JwtBlacklistService jwtBlacklistService;
    private final UserRepository userRepository;

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

                // A token we cannot check against the logout blacklist is not
                // trusted (see JwtBlacklistService for the Redis-outage policy).
                String jti = claims.get(JwtService.CLAIM_JTI, String.class);
                boolean revoked;
                try {
                    revoked = jwtBlacklistService.isBlacklisted(jti);
                } catch (DataAccessException redisDown) {
                    log.warn("Token revocation check unavailable: {}", redisDown.getMessage());
                    writeServiceUnavailable(response);
                    return;
                }
                if (revoked) {
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

    /** Same JSON shape as {@code GlobalExceptionHandler}. */
    private static void writeServiceUnavailable(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"AUTH_UNAVAILABLE\","
                + "\"message\":\"Session store unavailable; try again shortly\","
                + "\"status\":503,\"timestamp\":\"" + Instant.now() + "\"}");
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
