package com.codelens.dto.response;

import com.codelens.entity.Repository;
import com.codelens.service.RepoService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Public view of a connected repository. Hides the encrypted
 * webhook secret and access token — those never leave the service.
 *
 * <p>The {@code totalPrsReviewed}, {@code lastReviewedAt} and
 * {@code latestQualityScore} fields are aggregated by
 * {@link RepoService#listForOwner} and are suitable for the
 * dashboard's repo-list view.</p>
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
    public static RepoResponse from(Repository repo) {
        return new RepoResponse(
                repo.getId(),
                repo.getFullName(),
                repo.getGithubId(),
                repo.isActive(),
                repo.getCreatedAt(),
                0L,
                null,
                null
        );
    }

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
