package com.automatedcodereviewtool.repository;

import com.automatedcodereviewtool.entity.Repository;
import com.automatedcodereviewtool.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link com.automatedcodereviewtool.entity.Repository Repository} entities.
 *
 * <p>The class name shadows {@code org.springframework.stereotype.Repository}
 * so we don't import that annotation here — Spring Data registers the
 * bean automatically.</p>
 */
public interface RepositoryRepository extends JpaRepository<Repository, UUID> {
    Optional<Repository> findByGithubId(Long githubId);

    @Query("select r from Repository r join fetch r.owner where r.id = :id")
    Optional<Repository> findWithOwnerById(@Param("id") UUID id);

    /** The caller's connected repositories in a stable order (the dashboard list). */
    List<Repository> findAllByOwnerAndIsActiveTrueOrderByFullNameAsc(User owner);

    /**
     * PR aggregates for several repositories in one query: the number of
     * reviewed PRs and the latest review time. {@code MAX} ignores PRs that
     * were never reviewed, whatever the database's NULL ordering.
     */
    @Query("""
            SELECT p.repo.id AS repoId,
                   SUM(CASE WHEN p.status = 'reviewed' THEN 1 ELSE 0 END) AS reviewedCount,
                   MAX(p.reviewedAt) AS lastReviewedAt
            FROM PullRequestEntity p
            WHERE p.repo IN :repos
            GROUP BY p.repo.id
            """)
    List<PullRequestStats> summarizePullRequests(@Param("repos") Collection<Repository> repos);

    interface PullRequestStats {
        UUID getRepoId();
        Long getReviewedCount();
        Instant getLastReviewedAt();
    }
}
