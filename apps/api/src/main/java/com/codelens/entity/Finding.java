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
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "findings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Finding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pull_request_id", nullable = false)
    private PullRequestEntity pullRequest;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "line_start")
    private Integer lineStart;

    @Column(name = "line_end")
    private Integer lineEnd;

    @Column(name = "anti_pattern", nullable = false)
    private String antiPattern;

    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "severity", nullable = false)
    private String severity;

    @Column(name = "confidence", precision = 4, scale = 3, nullable = false)
    private BigDecimal confidence;

    @Column(name = "explanation", columnDefinition = "TEXT")
    private String explanation;

    @Column(name = "code_snippet", columnDefinition = "TEXT")
    private String codeSnippet;

    /**
     * User disposition: {@code "open"}, {@code "accepted"},
     * {@code "dismissed"}, {@code "fixed"}.
     */
    @Column(name = "status", nullable = false)
    @Builder.Default
    private String status = "open";

    @Column(name = "disposition_at")
    private Instant dispositionAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
