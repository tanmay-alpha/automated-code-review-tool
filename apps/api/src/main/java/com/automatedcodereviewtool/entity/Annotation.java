package com.automatedcodereviewtool.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A single annotation attached to a code sample.
 *
 * <p>Stored in {@code ml.annotations}. {@code label_state} records
 * the disposition of the annotation (positive / negative /
 * uncertain). {@code source} records who produced it.</p>
 */
@Entity
@Table(name = "annotations", schema = "ml")
public class Annotation {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "code_sample_id", nullable = false)
    private UUID codeSampleId;

    @Column(name = "anti_pattern_id", nullable = false, length = 80)
    private String antiPatternId;

    @Column(name = "label_state", nullable = false, length = 20)
    private String labelState;

    @Column(name = "line_start", nullable = false)
    private int lineStart;

    @Column(name = "line_end", nullable = false)
    private int lineEnd;

    @Column(name = "source", nullable = false, length = 30)
    private String source;

    @Column(name = "confidence")
    private BigDecimal confidence;

    @Column(name = "reviewer_user_id")
    private UUID reviewerUserId;

    @Column(name = "finding_id")
    private UUID findingId;

    @Column(name = "feedback_action", length = 20)
    private String feedbackAction;

    @Column(name = "idempotency_key", length = 100, unique = true)
    private String idempotencyKey;

    @Column(name = "supersedes_annotation_id")
    private UUID supersedesAnnotationId;

    @Column(name = "resolution_state", nullable = false, length = 20)
    @Builder.Default
    private String resolutionState = "active";

    @Column(name = "trust_level", length = 30)
    @Builder.Default
    private String trustLevel = "finding_feedback";

    @Column(name = "rationale", columnDefinition = "TEXT")
    private String rationale;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getCodeSampleId() { return codeSampleId; }
    public void setCodeSampleId(UUID codeSampleId) { this.codeSampleId = codeSampleId; }
    public String getAntiPatternId() { return antiPatternId; }
    public void setAntiPatternId(String antiPatternId) { this.antiPatternId = antiPatternId; }
    public String getLabelState() { return labelState; }
    public void setLabelState(String labelState) { this.labelState = labelState; }
    public int getLineStart() { return lineStart; }
    public void setLineStart(int lineStart) { this.lineStart = lineStart; }
    public int getLineEnd() { return lineEnd; }
    public void setLineEnd(int lineEnd) { this.lineEnd = lineEnd; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public UUID getReviewerUserId() { return reviewerUserId; }
    public void setReviewerUserId(UUID reviewerUserId) { this.reviewerUserId = reviewerUserId; }
    public UUID getFindingId() { return findingId; }
    public void setFindingId(UUID findingId) { this.findingId = findingId; }
    public String getFeedbackAction() { return feedbackAction; }
    public void setFeedbackAction(String feedbackAction) { this.feedbackAction = feedbackAction; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public UUID getSupersedesAnnotationId() { return supersedesAnnotationId; }
    public void setSupersedesAnnotationId(UUID supersedesAnnotationId) { this.supersedesAnnotationId = supersedesAnnotationId; }
    public String getResolutionState() { return resolutionState; }
    public void setResolutionState(String resolutionState) { this.resolutionState = resolutionState; }
    public String getTrustLevel() { return trustLevel; }
    public void setTrustLevel(String trustLevel) { this.trustLevel = trustLevel; }
    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}