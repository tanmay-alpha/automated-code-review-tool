package com.codelens.config;

import com.codelens.security.ApiKeyAuthFilter;
import com.codelens.security.AuthRateLimitFilter;
import com.codelens.security.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Stateless security configuration.
 *
 * <p>Three filters protect the API:</p>
 * <ul>
 *   <li>{@link ApiKeyAuthFilter} — runs first; activates <em>only</em>
 *       for {@code /api/scan/file} (the VS Code extension endpoint).
 *       Authenticates via {@code Authorization: Bearer cl_live_…}
 *       and applies a Redis rate limit.</li>
 *   <li>{@link AuthRateLimitFilter} — runs second; activates <em>only</em>
 *       for the public auth flow endpoints
 *       ({@code /api/auth/github}, {@code /api/auth/callback},
 *       {@code /api/auth/refresh}). Caps each client IP at 10 req/min
 *       so brute-force attempts on the OAuth handshake are shed early.</li>
 *   <li>{@link JwtAuthFilter} — runs last; validates the {@code accessToken}
 *       cookie and binds the {@link java.util.UUID} user id to the
 *       request principal.</li>
 * </ul>
 *
 * <p>CSRF is disabled because the API is stateless and does not use
 * cookie-based session auth.</p>
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AppConfig.class)
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ApiKeyAuthFilter apiKeyAuthFilter;
    private final AuthRateLimitFilter authRateLimitFilter;
    private final AppConfig appConfig;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
                          ApiKeyAuthFilter apiKeyAuthFilter,
                          AuthRateLimitFilter authRateLimitFilter,
                          AppConfig appConfig) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.apiKeyAuthFilter = apiKeyAuthFilter;
        this.authRateLimitFilter = authRateLimitFilter;
        this.appConfig = appConfig;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        new org.springframework.security.web.authentication.HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED)
                ))
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
                // Auth rate limit runs before JWT so brute-force attempts
                // on /api/auth/* are shed before any token work.
                .addFilterBefore(authRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(appConfig.getFrontendUrl()));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
