package com.automatedcodereviewtool.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Properties under the {@code app} prefix that don't belong to a more
 * specific config class: the web frontend URL we redirect to after the OAuth
 * callback, the browser origins allowed to make credentialed requests, and
 * the cookie {@code Secure} flag.
 */
@ConfigurationProperties(prefix = "app")
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    /** Where to send the browser after a successful OAuth login. */
    private String frontendUrl = "http://localhost:3000";

    /**
     * Browser origins ({@code scheme://host[:port]}) allowed to send
     * credentialed (cookie) requests: CORS and the CSRF origin check both use
     * this list. Empty means "only the origin of {@link #frontendUrl}".
     */
    private List<String> allowedOrigins = new ArrayList<>();

    /** Keep authentication cookies HTTPS-only outside local development. */
    private boolean cookieSecure = true;

    public String getFrontendUrl() {
        return frontendUrl;
    }

    public void setFrontendUrl(String frontendUrl) {
        if (frontendUrl != null && !frontendUrl.isBlank()) {
            try {
                URI uri = URI.create(frontendUrl);
                if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                    throw new IllegalArgumentException(
                            "app.frontend-url must use http:// or https:// scheme");
                }
                String host = uri.getHost();
                if (host == null || host.isBlank()) {
                    throw new IllegalArgumentException(
                            "app.frontend-url must contain a valid host");
                }
                if (!"localhost".equalsIgnoreCase(host)
                        && !"127.0.0.1".equals(host)
                        && !host.endsWith(".local")) {
                    log.warn("FRONTEND_URL host '{}' is not localhost — verify HTTPS is configured in production", host);
                }
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "app.frontend-url is not a valid absolute URL: " + frontendUrl, e);
            }
        }
        this.frontendUrl = frontendUrl;
    }

    /**
     * The normalized allowed origins: {@code app.allowed-origins} when set,
     * otherwise the origin of {@code app.frontend-url}.
     */
    public List<String> getAllowedOrigins() {
        if (!allowedOrigins.isEmpty()) {
            return List.copyOf(allowedOrigins);
        }
        return List.of(normalizeOrigin(frontendUrl, false));
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        List<String> normalized = new ArrayList<>();
        if (allowedOrigins != null) {
            for (String origin : allowedOrigins) {
                if (origin != null && !origin.isBlank()) {
                    normalized.add(normalizeOrigin(origin.trim(), true));
                }
            }
        }
        this.allowedOrigins = normalized;
    }

    /**
     * Serializes {@code value} the way browsers serialize the {@code Origin}
     * header: lowercase scheme and host, port only when it is not the scheme
     * default. Returns {@code null} for anything that is not an http(s) URL
     * with a host (including the opaque origin {@code "null"}).
     *
     * @param strict when {@code true}, a path, query, fragment or user-info
     *               component is an error (configured origins must be bare
     *               origins); when {@code false} they are ignored, as for a
     *               {@code Referer} or the frontend URL.
     */
    public static String normalizeOrigin(String value, boolean strict) {
        if (value == null || value.isBlank()) {
            return strict ? fail(value) : null;
        }
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException e) {
            return strict ? fail(value) : null;
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || host == null || host.isEmpty()) {
            return strict ? fail(value) : null;
        }
        if (strict) {
            String path = uri.getRawPath();
            if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (path != null && !path.isEmpty() && !"/".equals(path))) {
                return fail(value);
            }
        }
        int port = uri.getPort();
        boolean defaultPort = port == -1
                || ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }

    private static String fail(String value) {
        throw new IllegalArgumentException(
                "app.allowed-origins entries must be http(s)://host[:port] origins, got: " + value);
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }

    @jakarta.annotation.PostConstruct
    public void validateCookieSecurity() {
        String activeProfile = System.getProperty("spring.profiles.active", System.getenv("SPRING_PROFILES_ACTIVE"));
        if (!cookieSecure && activeProfile != null && !activeProfile.contains("dev") && !activeProfile.contains("test")) {
            log.warn("COOKIE_SECURE is disabled in a non-dev profile ('{}'). Auth cookies will be sent over unencrypted HTTP.", activeProfile);
        }
    }
}
