package com.automatedcodereviewtool.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
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
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "repositories")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Repository {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "github_id", unique = true, nullable = false)
    private Long githubId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "description")
    private String description;

    @Column(name = "is_private", nullable = false)
    private boolean isPrivate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "webhook_id")
    private Long webhookId;

    @Column(name = "webhook_secret")
    private String webhookSecret;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "quality_score", precision = 5, scale = 2)
    private BigDecimal qualityScore;

    @Column(name = "license_spdx", length = 30)
    private String licenseSpdx;

    @Column(name = "data_use_status", nullable = false, length = 40)
    @Builder.Default
    private String dataUseStatus = "quarantined_unknown_license";

    @Column(name = "consent_recorded_at")
    private Instant consentRecordedAt;

    // --- review settings (GET/PUT /api/repos/{id}/settings, RepoReviewPolicy) ---

    /** Taxonomy ids whose findings are reported; empty means every rule. */
    @Convert(converter = CommaSeparatedListConverter.class)
    @Column(name = "review_enabled_rules", nullable = false, length = 2000)
    @Builder.Default
    private List<String> reviewEnabledRules = List.of();

    /** Lowest severity reported: {@code minor}, {@code major} or {@code critical}. */
    @Column(name = "review_min_severity", nullable = false, length = 10)
    @Builder.Default
    private String reviewMinSeverity = "minor";

    /** Findings below this confidence (0..1) are dropped. */
    @Column(name = "review_min_confidence", nullable = false, precision = 4, scale = 3)
    @Builder.Default
    private BigDecimal reviewMinConfidence = BigDecimal.ZERO;

    /** Whether the review summary is posted to the pull request. */
    @Column(name = "review_post_comments", nullable = false)
    @Builder.Default
    private boolean reviewPostComments = true;

    /** Whether findings are posted as inline review comments. */
    @Column(name = "review_inline_comments", nullable = false)
    @Builder.Default
    private boolean reviewInlineComments = false;

    /** Subset of {@link #REVIEW_LANGUAGES} whose files are reviewed. */
    @Convert(converter = CommaSeparatedListConverter.class)
    @Column(name = "review_languages", nullable = false, length = 100)
    @Builder.Default
    private List<String> reviewLanguages = REVIEW_LANGUAGES;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Languages a repository can enable, in canonical order (also the default). */
    public static final List<String> REVIEW_LANGUAGES = List.of("python", "javascript", "typescript", "java");

    /**
     * Stores a list of identifiers as one comma-separated column. Both
     * vocabularies (taxonomy ids, language names) are {@code [A-Za-z0-9_]},
     * which the V20 CHECK constraints enforce.
     */
    @Converter
    public static class CommaSeparatedListConverter implements AttributeConverter<List<String>, String> {
        @Override
        public String convertToDatabaseColumn(List<String> values) {
            return values == null ? "" : String.join(",", values);
        }

        @Override
        public List<String> convertToEntityAttribute(String column) {
            return column == null || column.isEmpty() ? List.of() : List.of(column.split(","));
        }
    }
}
