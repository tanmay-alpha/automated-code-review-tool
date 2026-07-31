package com.automatedcodereviewtool.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Records ALL detector output, including results that cannot become
 * normal findings (unmapped files, invalid lines, etc.).
 *
 * <p>Stored in {@code ml.prediction_events}. This table preserves
 * ML debugging evidence without misattributing unlocalized predictions
 * to incorrect hunks.</p>
 *
 * <p>Never stores raw source code or secrets in {@code raw_metadata}.</p>
 */
@Entity
@Table(name = "prediction_events", schema = "ml")
public class PredictionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "code_sample_id")
    private UUID codeSampleId;

    @Column(name = "pull_request_id", nullable = false)
    private UUID pullRequestId;

    @Column(name = "file_path", columnDefinition = "TEXT")
    private String filePath;

    @Column(name = "reported_line_start")
    private Integer reportedLineStart;

    @Column(name = "reported_line_end")
    private Integer reportedLineEnd;

    @Column(name = "anti_pattern_id", nullable = false, length = 80)
    private String antiPatternId;

    @Column(name = "category", nullable = false, length = 30)
    private String category;

    @Column(name = "severity", nullable = false, length = 10)
    private String severity;

    @Column(name = "confidence", precision = 5, scale = 4, nullable = false)
    private BigDecimal confidence;

    @Column(name = "engine", nullable = false, length = 60)
    private String engine;

    @Column(name = "model_version", nullable = false, length = 100)
    private String modelVersion;

    @Column(name = "taxonomy_version", nullable = false, length = 40)
    private String taxonomyVersion;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "rejection_reason", length = 100)
    private String rejectionReason;

    @Column(name = "raw_metadata", columnDefinition = "JSONB")
    private String rawMetadata;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (status == null) {
            status = "persisted";
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getCodeSampleId() { return codeSampleId; }
    public void setCodeSampleId(UUID codeSampleId) { this.codeSampleId = codeSampleId; }
    public UUID getPullRequestId() { return pullRequestId; }
    public void setPullRequestId(UUID pullRequestId) { this.pullRequestId = pullRequestId; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public Integer getReportedLineStart() { return reportedLineStart; }
    public void setReportedLineStart(Integer reportedLineStart) { this.reportedLineStart = reportedLineStart; }
    public Integer getReportedLineEnd() { return reportedLineEnd; }
    public void setReportedLineEnd(Integer reportedLineEnd) { this.reportedLineEnd = reportedLineEnd; }
    public String getAntiPatternId() { return antiPatternId; }
    public void setAntiPatternId(String antiPatternId) { this.antiPatternId = antiPatternId; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
    public String getTaxonomyVersion() { return taxonomyVersion; }
    public void setTaxonomyVersion(String taxonomyVersion) { this.taxonomyVersion = taxonomyVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public String getRawMetadata() { return rawMetadata; }
    public void setRawMetadata(String rawMetadata) { this.rawMetadata = rawMetadata; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
