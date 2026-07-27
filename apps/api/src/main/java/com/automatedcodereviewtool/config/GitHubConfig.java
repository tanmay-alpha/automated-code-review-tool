package com.codelens.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Properties under the {@code app.github} prefix.
 */
@ConfigurationProperties(prefix = "app.github")
public class GitHubConfig {

    private String clientId;
    private String clientSecret;
    private String oauthRedirectUri;

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getOauthRedirectUri() {
        return oauthRedirectUri;
    }

    public void setOauthRedirectUri(String oauthRedirectUri) {
        this.oauthRedirectUri = oauthRedirectUri;
    }
}
