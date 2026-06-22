package com.codelens.config;

import com.codelens.security.ApiKeyAuthFilter;
import com.codelens.security.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Stateless security configuration.
 *
 * <p>Two filters protect the API:</p>
 * <ul>
 *   <li>{@link ApiKeyAuthFilter} — runs first; activates <em>only</em>
 *       for {@code /api/scan/file} (the VS Code extension endpoint).
 *       Authenticates via {@code Authorization: Bearer cl_live_…}
 *       and applies a Redis rate limit.</li>
 *   <li>{@link JwtAuthFilter} — runs for everything else; validates
 *       the {@code accessToken} cookie and binds the {@link java.util.UUID}
 *       user id to the request principal.</li>
 * </ul>
 *
 * <p>CSRF is disabled because the API is stateless and does not use
 * cookie-based session auth.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ApiKeyAuthFilter apiKeyAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
                          ApiKeyAuthFilter apiKeyAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.apiKeyAuthFilter = apiKeyAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/auth/github",
                                "/api/auth/callback",
                                "/api/auth/refresh",
                                "/api/webhook/github",
                                "/actuator/health").permitAll()
                        // /api/scan/file accepts either a JWT (dashboard
                        // curl tests) or an API key (VS Code extension).
                        // The API key filter handles its own 403/429.
                        .requestMatchers("/api/scan/file").hasAnyRole("USER", "API_KEY")
                        .anyRequest().authenticated())
                // API key filter must run before JWT filter so a key
                // never falls through to JWT cookie validation.
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
