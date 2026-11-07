package com.automatedcodereviewtool.dto.response;

import com.automatedcodereviewtool.entity.PullRequestEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code GET /api/repos/{id}/prs}. Deliberately excludes the
 * repository association so the webhook secret and owner token can never
 * be serialized.
 */
public record PullRequestSummary(
        UUID id,
        int githubPrNumber,
        String title,
        String authorGithub,
        String headSha,
        String githubPrUrl,
        String status,
        BigDecimal qualityScore,
        Long githubCommentId,
        String errorMessage,
        Instant reviewedAt,
        Instant createdAt
) {
    public static PullRequestSummary from(PullRequestEntity pr) {
        return new PullRequestSummary(
                pr.getId(),
                pr.getGithubPrNumber(),
                pr.getTitle(),
                pr.getAuthorGithub(),
                pr.getHeadSha(),
                pr.getGithubPrUrl(),
                pr.getStatus(),
                pr.getQualityScore(),
                pr.getGithubCommentId(),
                pr.getErrorMessage(),
                pr.getReviewedAt(),
                pr.getCreatedAt());
    }
}
