package com.codelens.repository;

import com.codelens.entity.PullRequestEntity;
import com.codelens.entity.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PullRequestRepository extends JpaRepository<PullRequestEntity, UUID> {
    Optional<PullRequestEntity> findByRepoAndGithubPrNumber(Repository repo, int githubPrNumber);

    /**
     * Page of PRs for a repo, paginated + sorted by the caller.
     * Used by {@code GET /api/repos/{id}/prs}.
     */
    Page<PullRequestEntity> findAllByRepo(Repository repo, Pageable pageable);

    /** Total PRs in a repo with a given lifecycle status. */
    long countByRepoAndStatus(Repository repo, String status);

    /** Most recently reviewed PR for a repo (null if none). */
    Optional<PullRequestEntity> findFirstByRepoOrderByReviewedAtDesc(Repository repo);
}