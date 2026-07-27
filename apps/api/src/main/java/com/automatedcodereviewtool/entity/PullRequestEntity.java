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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pull_requests")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PullRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "github_pr_number", nullable = false)
    private int githubPrNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repo_id", nullable = false)
    private Repository repo;

    @Column(name = "title")
    private String title;

    @Column(name = "author_github")
    private String authorGithub;

    @Column(name = "head_sha")
    private String headSha;

    @Column(name = "github_pr_url")
    private String githubPrUrl;

    @Column(name = "status", nullable = false)
    @Builder.Default
    private String status = "pending";

    @Column(name = "quality_score", precision = 5, scale = 2)
    private BigDecimal qualityScore;

    @Column(name = "github_comment_id")
    private Long githubCommentId;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
