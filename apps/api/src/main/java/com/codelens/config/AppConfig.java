package com.codelens.config;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Properties under the {@code app} prefix that don't belong to a more
 * specific config class. Today: the web frontend URL we redirect to
 * after the OAuth callback.
 */
@ConfigurationProperties(prefix = "app")
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    /** Where to send the browser after a successful OAuth login. */
    private String frontendUrl = "http://localhost:3000";

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

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }
}
