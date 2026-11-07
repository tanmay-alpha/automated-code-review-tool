package com.automatedcodereviewtool.dto.response;

import com.automatedcodereviewtool.entity.Repository;
import com.automatedcodereviewtool.service.RepoService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Public view of a connected repository, used by every repo endpoint.
 * Hides the encrypted webhook secret and the owner's access token.
 *
 * <p>{@code totalPrsReviewed}, {@code lastReviewedAt} and
 * {@code latestQualityScore} (mean of the daily averages over the last
 * 30 days) come from {@link RepoService.RepoSummary}.</p>
 */
public record RepoResponse(
        UUID id,
        String fullName,
        Long githubId,
        boolean active,
        Instant createdAt,
        long totalPrsReviewed,
        Instant lastReviewedAt,
        BigDecimal latestQualityScore
) {
    public static RepoResponse from(RepoService.RepoSummary s) {
        Repository repo = s.repo();
        return new RepoResponse(
                repo.getId(),
                repo.getFullName(),
                repo.getGithubId(),
                repo.isActive(),
                repo.getCreatedAt(),
                s.totalPrsReviewed(),
                s.lastReviewedAt(),
                s.latestQualityScore()
        );
    }
}
