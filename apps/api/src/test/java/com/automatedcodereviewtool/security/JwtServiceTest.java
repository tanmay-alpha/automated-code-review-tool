package com.automatedcodereviewtool.security;

import com.automatedcodereviewtool.config.JwtConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JwtService}.
 *
 * <p>We construct the service directly with a hand-built {@link JwtConfig}
 * so the suite does not depend on Spring or any external secret.</p>
 */
class JwtServiceTest {

    /** 32 bytes = 256 bits, the minimum key length for HS256. */
    private static final String TEST_SECRET =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    private JwtConfig config;
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        config = new JwtConfig();
        config.setSecret(TEST_SECRET);
        config.setAccessTokenExpiry(900);
        config.setRefreshTokenExpiry(604_800);
        jwtService = new JwtService(config);
    }

    @Test
    void testGenerateAndValidateAccessToken() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(userId, "tanmay-alpha", 0);

        Claims claims = jwtService.validateToken(token);

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get(JwtService.CLAIM_TYPE)).isEqualTo(JwtService.TYPE_ACCESS);
        assertThat(claims.get(JwtService.CLAIM_USERNAME)).isEqualTo("tanmay-alpha");
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }

    @Test
    void testExpiredTokenThrowsException() {
        JwtConfig shortLived = new JwtConfig();
        shortLived.setSecret(TEST_SECRET);
        // Negative expiry = the token is born expired.
        shortLived.setAccessTokenExpiry(-10);
        shortLived.setRefreshTokenExpiry(604_800);
        JwtService shortService = new JwtService(shortLived);

        String token = shortService.generateAccessToken(UUID.randomUUID(), "u", 0);

        assertThatThrownBy(() -> shortService.validateToken(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void testSubjectIsUserId() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(userId, "u", 0);

        assertThat(jwtService.validateToken(token).getSubject()).isEqualTo(userId.toString());
    }

    @Test
    void testRefreshTokenDifferentFromAccessToken() {
        UUID userId = UUID.randomUUID();
        String access = jwtService.generateAccessToken(userId, "u", 0);
        String refresh = jwtService.generateRefreshToken(userId, 0);

        assertThat(access).isNotEqualTo(refresh);

        Claims accessClaims = jwtService.validateToken(access);
        Claims refreshClaims = jwtService.validateToken(refresh);
        assertThat(accessClaims.get(JwtService.CLAIM_TYPE)).isEqualTo(JwtService.TYPE_ACCESS);
        assertThat(refreshClaims.get(JwtService.CLAIM_TYPE)).isEqualTo(JwtService.TYPE_REFRESH);
        // Access tokens carry the username claim; refresh tokens don't.
        assertThat(accessClaims.get(JwtService.CLAIM_USERNAME)).isEqualTo("u");
        assertThat(refreshClaims.get(JwtService.CLAIM_USERNAME)).isNull();
    }

    @Test
    void tokensCarryTheUserTokenVersion() {
        UUID userId = UUID.randomUUID();
        Claims access = jwtService.validateToken(jwtService.generateAccessToken(userId, "u", 3));
        Claims refresh = jwtService.validateToken(jwtService.generateRefreshToken(userId, 3));

        assertThat(JwtService.isCurrentVersion(access, 3)).isTrue();
        assertThat(JwtService.isCurrentVersion(refresh, 3)).isTrue();
        // After "sign out everywhere" bumps the version, both are stale.
        assertThat(JwtService.isCurrentVersion(access, 4)).isFalse();
        assertThat(JwtService.isCurrentVersion(refresh, 4)).isFalse();
    }

    @Test
    void tokensWithoutVersionClaimCountAsVersionZero() {
        Claims legacy = io.jsonwebtoken.Jwts.claims().subject("x").build();

        assertThat(JwtService.isCurrentVersion(legacy, 0)).isTrue();
        assertThat(JwtService.isCurrentVersion(legacy, 1)).isFalse();
    }
}
