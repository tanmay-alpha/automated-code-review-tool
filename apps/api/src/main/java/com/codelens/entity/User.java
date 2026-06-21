package com.codelens.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "github_id", unique = true, nullable = false)
    private Long githubId;

    @Column(name = "github_username")
    private String githubUsername;

    @Column(name = "avatar_url")
    private String avatarUrl;

    /** GitHub OAuth access token, AES-encrypted at rest. */
    @Column(name = "access_token")
    private String accessToken;

    /** SHA-256 hash of the GitHub OAuth refresh token. */
    @Column(name = "refresh_token_hash")
    private String refreshTokenHash;

    /** SHA-256 hash of the user's CLI / dashboard API key. */
    @Column(name = "api_key_hash")
    private String apiKeyHash;

    /** First 8 chars of api_key, for display + lookup. */
    @Column(name = "api_key_prefix")
    private String apiKeyPrefix;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
