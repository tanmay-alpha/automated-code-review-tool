package com.automatedcodereviewtool.config;

import com.automatedcodereviewtool.security.ApiKeyAuthFilter;
import com.automatedcodereviewtool.security.AuthRateLimitFilter;
import com.automatedcodereviewtool.security.JwtAuthFilter;
import com.automatedcodereviewtool.security.OriginVerificationFilter;
import com.automatedcodereviewtool.security.RequestSizeLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * Stateless security configuration.
 *
 * <p>Filters in front of Spring Security's authorization rules:</p>
 * <ul>
 *   <li>{@link RequestSizeLimitFilter} — rejects oversized query strings,
 *       headers and declared request bodies.</li>
 *   <li>{@link ApiKeyAuthFilter} — activates <em>only</em> for
 *       {@code /api/scan/file} (VS Code extension / GitHub Action).
 *       Authenticates via {@code Authorization: Bearer cl_live_…}
 *       and applies a per-user rate limit.</li>
 *   <li>{@link AuthRateLimitFilter} — activates <em>only</em> for the
 *       public auth endpoints ({@code /api/auth/github},
 *       {@code /api/auth/callback}, {@code /api/auth/refresh}) and caps
 *       each client IP at 10 req/min.</li>
 *   <li>{@link JwtAuthFilter} — validates the {@code accessToken} cookie
 *       (or a JWT bearer header) and binds the {@code User} entity as the
 *       request principal.</li>
 *   <li>{@link OriginVerificationFilter} — runs before CORS and rejects
 *       unsafe cookie-authenticated requests whose {@code Origin}
 *       ({@code Sec-Fetch-Site}/{@code Referer} when Origin is absent) is
 *       not an allowed frontend origin.</li>
 * </ul>
 *
 * <p>CSRF tokens are disabled. CSRF protection for the cookie-authenticated
 * dashboard is the origin check above, backed by {@code SameSite=Lax}
 * cookies and CORS allowing only {@code app.allowed-origins}. See
 * docs/security-review.md for the browser-behaviour analysis.</p>
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({AppConfig.class, WebhookConfig.class})
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ApiKeyAuthFilter apiKeyAuthFilter;
    private final AuthRateLimitFilter authRateLimitFilter;
    private final AppConfig appConfig;
    private final RequestSizeLimitFilter requestSizeLimitFilter;
    private final OriginVerificationFilter originVerificationFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
                          ApiKeyAuthFilter apiKeyAuthFilter,
                          AuthRateLimitFilter authRateLimitFilter,
                          AppConfig appConfig,
                          RequestSizeLimitFilter requestSizeLimitFilter,
                          OriginVerificationFilter originVerificationFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.apiKeyAuthFilter = apiKeyAuthFilter;
        this.authRateLimitFilter = authRateLimitFilter;
        this.appConfig = appConfig;
        this.requestSizeLimitFilter = requestSizeLimitFilter;
        this.originVerificationFilter = originVerificationFilter;
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
                        // The API key filter handles its own 401/429.
                        .requestMatchers("/api/scan/file").hasAnyRole("USER", "API_KEY")
                        .anyRequest().authenticated())
                // API key filter must run before JWT filter so a key
                // never falls through to JWT cookie validation.
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // Auth rate limit runs before JWT so brute-force attempts
                // on /api/auth/* are shed before any token work.
                .addFilterBefore(authRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(requestSizeLimitFilter, ApiKeyAuthFilter.class)
                // Origin check precedes CORS so a cross-site cookie request is
                // refused by this filter regardless of the CORS settings.
                .addFilterBefore(originVerificationFilter, CorsFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(appConfig.getAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /** Registered inside the security chain only; don't also run it as a plain servlet filter. */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilterRegistration(RequestSizeLimitFilter filter) {
        org.springframework.boot.web.servlet.FilterRegistrationBean<RequestSizeLimitFilter> registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Authentication is JWT/API-key only. Declaring a UserDetailsService that
     * knows no users stops Spring Boot's UserDetailsServiceAutoConfiguration
     * from creating an in-memory "user" and logging its generated password.
     */
    @Bean
    public org.springframework.security.core.userdetails.UserDetailsService userDetailsService() {
        return username -> {
            throw new org.springframework.security.core.userdetails.UsernameNotFoundException(
                    "Username/password authentication is not supported");
        };
    }

    /** Registered inside the security chain only (before CORS). */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<OriginVerificationFilter> originVerificationFilterRegistration(OriginVerificationFilter filter) {
        org.springframework.boot.web.servlet.FilterRegistrationBean<OriginVerificationFilter> registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
