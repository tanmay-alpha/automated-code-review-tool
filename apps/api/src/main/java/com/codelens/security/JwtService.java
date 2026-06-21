package com.codelens.security;

import com.codelens.config.JwtConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and verifies HS256 JWT access + refresh tokens.
 *
 * <p>Token shape:</p>
 * <ul>
 *   <li>{@code sub} = {@code userId} (UUID, stringified)</li>
 *   <li>{@code type} = {@code "access"} or {@code "refresh"}</li>
 *   <li>{@code username} = GitHub login (access tokens only)</li>
 * </ul>
 *
 * <p>Throws {@link JwtException} on any parse/verify failure (bad signature,
 * expired, malformed). Callers should catch this and return 401.</p>
 */
@Service
@EnableConfigurationProperties(JwtConfig.class)
public class JwtService {

    static final String CLAIM_TYPE = "type";
    static final String CLAIM_USERNAME = "username";
    static final String TYPE_ACCESS = "access";
    static final String TYPE_REFRESH = "refresh";

    private final JwtConfig config;
    private final SecretKey signingKey;

    public JwtService(JwtConfig config) {
        if (config.getSecret() == null || config.getSecret().isBlank()) {
            throw new IllegalStateException("app.jwt.secret must be configured");
        }
        this.config = config;
        this.signingKey = buildKey(config.getSecret());
    }

    /**
     * The HS256 key must be >= 256 bits = 32 bytes. We accept the secret
     * either as raw UTF-8 bytes (>= 32 chars) or as base64-encoded raw bytes.
     */
    private static SecretKey buildKey(String secret) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException notBase64) {
            raw = secret.getBytes(StandardCharsets.UTF_8);
        }
        if (raw.length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret must decode to >= 32 bytes for HS256");
        }
        return io.jsonwebtoken.security.Keys.hmacShaKeyFor(raw);
    }

    public String generateAccessToken(UUID userId, String githubUsername) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .claim(CLAIM_USERNAME, githubUsername)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(config.getAccessTokenExpiry())))
                .signWith(signingKey)
                .compact();
    }

    public String generateRefreshToken(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(config.getRefreshTokenExpiry())))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Verifies signature + expiry and returns the claims body.
     *
     * @throws JwtException if the token is invalid, expired, or malformed.
     */
    public Claims validateToken(String token) {
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token);
        return jws.getPayload();
    }

    /**
     * Convenience: extract the {@code sub} claim as a UUID.
     *
     * <p>Caller should already have validated the token via {@link #validateToken(String)}.</p>
     */
    public UUID extractUserId(String token) {
        Claims claims = validateToken(token);
        return UUID.fromString(claims.getSubject());
    }
}
