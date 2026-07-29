package com.automatedcodereviewtool.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Canonical anti-pattern taxonomy row.
 *
 * <p>Populated at build time from {@code taxonomy/anti_patterns.yaml}
 * via the V5 Flyway migration. The application reads it through
 * {@link com.automatedcodereviewtool.repository.AntiPatternRepository}
 * and {@link com.automatedcodereviewtool.service.TaxonomyService}.</p>
 */
@Entity
@Table(
        name = "anti_patterns",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_anti_pattern_id", columnNames = "id")
        }
)
public class AntiPattern {

    @Id
    @Column(name = "id", length = 80, nullable = false)
    private String id;

    @Column(name = "display_name", length = 120, nullable = false)
    private String displayName;

    @Column(name = "category", length = 30, nullable = false)
    private String category;

    @Column(name = "default_severity", length = 10, nullable = false)
    private String defaultSeverity;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "trainable", nullable = false)
    private boolean trainable = true;

    @Column(name = "updated_at", nullable = false)
    private java.time.OffsetDateTime updatedAt;

    public AntiPattern() {}

    public AntiPattern(String id, String displayName, String category, String defaultSeverity, String description, boolean trainable, java.time.OffsetDateTime updatedAt) {
        this.id = id;
        this.displayName = displayName;
        this.category = category;
        this.defaultSeverity = defaultSeverity;
        this.description = description;
        this.trainable = trainable;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getCategory() { return category; }
    public String getDefaultSeverity() { return defaultSeverity; }
    public String getDescription() { return description; }
    public boolean isTrainable() { return trainable; }
    public java.time.OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setId(String id) { this.id = id; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setCategory(String category) { this.category = category; }
    public void setDefaultSeverity(String defaultSeverity) { this.defaultSeverity = defaultSeverity; }
    public void setDescription(String description) { this.description = description; }
    public void setTrainable(boolean trainable) { this.trainable = trainable; }
    public void setUpdatedAt(java.time.OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
