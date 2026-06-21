package com.codelens.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Properties under the {@code app} prefix that don't belong to a more
 * specific config class. Today: the web frontend URL we redirect to
 * after the OAuth callback.
 */
@ConfigurationProperties(prefix = "app")
public class AppConfig {

    /** Where to send the browser after a successful OAuth login. */
    private String frontendUrl = "http://localhost:3000";

    public String getFrontendUrl() {
        return frontendUrl;
    }

    public void setFrontendUrl(String frontendUrl) {
        this.frontendUrl = frontendUrl;
    }
}
