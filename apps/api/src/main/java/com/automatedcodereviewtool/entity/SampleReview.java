package com.automatedcodereviewtool.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Tracks the review completion state of a code sample.
 *
 * <p>Stored in {@code ml.sample_reviews}. A sample without a positive
 * annotation is NOT automatically negative. A sample is treated as
 * fully negative only when {@code cleanConfirmed=true} and the review
 * is {@code complete}.</p>
 */
@Entity
@Table(name = "sample_reviews", schema = "ml")
public class SampleReview {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "code_sample_id", nullable = false)
    private UUID codeSampleId;

    @Column(name = "reviewer_user_id")
    private UUID reviewerUserId;

    @Column(name = "review_status", nullable = false, length = 20)
    private String reviewStatus = "unreviewed";

    @Column(name = "reviewed_label_ids", columnDefinition = "JSONB")
    private String reviewedLabelIds;

    @Column(name = "clean_confirmed", nullable = false)
    private Boolean cleanConfirmed = false;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (reviewStatus == null) {
            reviewStatus = "unreviewed";
        }
        if (cleanConfirmed == null) {
            cleanConfirmed = false;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getCodeSampleId() { return codeSampleId; }
    public void setCodeSampleId(UUID codeSampleId) { this.codeSampleId = codeSampleId; }
    public UUID getReviewerUserId() { return reviewerUserId; }
    public void setReviewerUserId(UUID reviewerUserId) { this.reviewerUserId = reviewerUserId; }
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }
    public String getReviewedLabelIds() { return reviewedLabelIds; }
    public void setReviewedLabelIds(String reviewedLabelIds) { this.reviewedLabelIds = reviewedLabelIds; }
    public Boolean getCleanConfirmed() { return cleanConfirmed; }
    public void setCleanConfirmed(Boolean cleanConfirmed) { this.cleanConfirmed = cleanConfirmed; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
