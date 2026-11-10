package com.automatedcodereviewtool.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Properties under the {@code app.github} prefix.
 */
@ConfigurationProperties(prefix = "app.github")
public class GitHubConfig {

    private String clientId;
    private String clientSecret;
    private String oauthRedirectUri;

    /**
     * Space-separated OAuth scopes requested at login. The default keeps
     * private-repository support ({@code repo}); public-only deployments can
     * use {@code public_repo admin:repo_hook}. See docs/security-review.md.
     */
    private String oauthScopes = "read:user repo";

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

    public String getOauthScopes() {
        return oauthScopes;
    }

    public void setOauthScopes(String oauthScopes) {
        this.oauthScopes = oauthScopes;
    }
}
