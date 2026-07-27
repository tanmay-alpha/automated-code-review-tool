package com.codelens.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * API key issued to a user for the VS Code extension
 * (or any other programmatic client).
 *
 * <p>We never store the full key in plaintext. Only the
 * <em>prefix</em> ({@code cl_live_} + first 8 hex chars) is kept
 * in cleartext for fast O(1) lookup; the remainder is bcrypt-hashed
 * and verified on each request via
 * {@link com.codelens.security.ApiKeyAuthFilter}.</p>
 */
@Entity
@Table(name = "api_keys")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "label", nullable = false, length = 60)
    private String label;

    /** First 16 characters of the key ({@code cl_live_} + 8 hex chars). */
    @Column(name = "prefix", nullable = false, length = 16, unique = true)
    private String prefix;

    /** bcrypt hash of the full key. */
    @Column(name = "key_hash", nullable = false, length = 80)
    private String keyHash;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;
}
