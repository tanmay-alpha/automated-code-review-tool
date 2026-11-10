package com.automatedcodereviewtool.entity;

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

import java.time.Instant;
import java.util.UUID;

/**
 * Publication of one reviewed PR head to GitHub (V22). Created in the review
 * transaction; published by {@code ReviewPublisher} under an attempt-fenced
 * lease. See V22 for the status machine.
 */
@Entity
@Table(name = "review_publications")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewPublication {

    public static final String PENDING = "pending";
    public static final String PUBLISHED = "published";
    public static final String FAILED = "failed";
    public static final String SUPERSEDED = "superseded";
    public static final String SKIPPED = "skipped";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "delivery_id", nullable = false, length = 100, updatable = false)
    private String deliveryId;

    @Column(name = "pr_id", nullable = false, updatable = false)
    private UUID prId;

    @Column(name = "head_sha", nullable = false, length = 40)
    private String headSha;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = PENDING;

    @Column(name = "inline_comments", nullable = false)
    private boolean inlineComments;

    @Column(name = "engine", length = 60)
    private String engine;

    /** JSON array of finding ids eligible for inline comments (added lines, ranked). */
    @Column(name = "inline_finding_ids", columnDefinition = "TEXT")
    private String inlineFindingIds;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    @Builder.Default
    private int maxAttempts = 8;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "comment_id")
    private Long commentId;

    @Column(name = "comment_url", columnDefinition = "TEXT")
    private String commentUrl;

    @Column(name = "review_id")
    private Long reviewId;

    @Column(name = "published_head_sha", length = 40)
    private String publishedHeadSha;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
