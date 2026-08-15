package com.automatedcodereviewtool.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable webhook-delivery state. The delivery id provides exact
 * idempotency while the database also enforces repo/PR/head deduplication.
 */
@Entity
@Table(name = "processed_webhooks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedWebhook {

    /** GitHub's {@code X-GitHub-Delivery} header (UUID-like). */
    @Id
    @Column(name = "delivery_id", length = 100)
    private String deliveryId;

    @CreationTimestamp
    @Column(name = "processed_at", updatable = false)
    private Instant processedAt;

    @Column(name = "repo_id")
    private UUID repoId;

    @Column(name = "github_pr_number")
    private Integer githubPrNumber;

    @Column(name = "head_sha", length = 40)
    private String headSha;

    @Column(name = "action", length = 30)
    private String action;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "received";

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(name = "max_attempts", nullable = false)
    @Builder.Default
    private int maxAttempts = 5;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
