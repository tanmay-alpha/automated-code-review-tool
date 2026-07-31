package com.automatedcodereviewtool.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Immutable dataset release metadata.
 *
 * <p>Stored in {@code ml.dataset_versions}. Once {@code status} is
 * {@code 'frozen'} no linked samples, annotations or items may be
 * modified.</p>
 */
@Entity
@Table(name = "dataset_versions", schema = "ml")
public class DatasetVersion {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "version", nullable = false, length = 40)
    private String version;

    @Column(name = "taxonomy_version", nullable = false, length = 40)
    private String taxonomyVersion;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "generation_config", nullable = false, columnDefinition = "JSONB")
    private String generationConfig;

    @Column(name = "manifest_sha256", nullable = false, columnDefinition = "CHAR(64)", length = 64)
    private String manifestSha256;

    @Column(name = "sample_count", nullable = false)
    private int sampleCount;

    @Column(name = "positive_annotation_count", nullable = false)
    private int positiveAnnotationCount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "frozen_at")
    private OffsetDateTime frozenAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getTaxonomyVersion() { return taxonomyVersion; }
    public void setTaxonomyVersion(String taxonomyVersion) { this.taxonomyVersion = taxonomyVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getGenerationConfig() { return generationConfig; }
    public void setGenerationConfig(String generationConfig) { this.generationConfig = generationConfig; }
    public String getManifestSha256() { return manifestSha256; }
    public void setManifestSha256(String manifestSha256) { this.manifestSha256 = manifestSha256; }
    public int getSampleCount() { return sampleCount; }
    public void setSampleCount(int sampleCount) { this.sampleCount = sampleCount; }
    public int getPositiveAnnotationCount() { return positiveAnnotationCount; }
    public void setPositiveAnnotationCount(int positiveAnnotationCount) { this.positiveAnnotationCount = positiveAnnotationCount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getFrozenAt() { return frozenAt; }
    public void setFrozenAt(OffsetDateTime frozenAt) { this.frozenAt = frozenAt; }
}