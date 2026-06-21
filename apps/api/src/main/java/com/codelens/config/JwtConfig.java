package com.codelens.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Properties under the {@code app.jwt} prefix in application.yml.
 *
 * <p>Bound to {@link com.codelens.security.JwtService} on startup.
 * The {@code secret} must be at least 32 bytes (HS256 minimum key size);
 * the application will fail to start otherwise.</p>
 */
@ConfigurationProperties(prefix = "app.jwt")
public class JwtConfig {

    /** Base64-encoded HMAC-SHA256 secret. Must decode to >= 32 bytes. */
    private String secret;

    /** Access-token lifetime in seconds (default 900 = 15 minutes). */
    private long accessTokenExpiry;

    /** Refresh-token lifetime in seconds (default 604800 = 7 days). */
    private long refreshTokenExpiry;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getAccessTokenExpiry() {
        return accessTokenExpiry;
    }

    public void setAccessTokenExpiry(long accessTokenExpiry) {
        this.accessTokenExpiry = accessTokenExpiry;
    }

    public long getRefreshTokenExpiry() {
        return refreshTokenExpiry;
    }

    public void setRefreshTokenExpiry(long refreshTokenExpiry) {
        this.refreshTokenExpiry = refreshTokenExpiry;
    }
}
