package com.automatedcodereviewtool.repository;

import com.automatedcodereviewtool.entity.PullRequestEntity;
import com.automatedcodereviewtool.entity.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface PullRequestRepository extends JpaRepository<PullRequestEntity, UUID> {
    Optional<PullRequestEntity> findByRepoAndGithubPrNumber(Repository repo, int githubPrNumber);

    /** PR with its repository and owner loaded in one query (no open-in-view needed). */
    @EntityGraph(attributePaths = {"repo", "repo.owner"})
    Optional<PullRequestEntity> findWithRepoAndOwnerById(UUID id);

    /**
     * PR row locked for update. Every write of review state (head SHA,
     * status, findings) happens under this lock and re-checks the head, so
     * a slow review of an older head cannot overwrite a newer push.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PullRequestEntity p where p.id = :id")
    Optional<PullRequestEntity> findForUpdateById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PullRequestEntity p where p.repo = :repo and p.githubPrNumber = :number")
    Optional<PullRequestEntity> findForUpdateByRepoAndGithubPrNumber(@Param("repo") Repository repo,
                                                                     @Param("number") int githubPrNumber);

    @Query("select p.headSha from PullRequestEntity p where p.id = :id")
    Optional<String> findHeadShaById(@Param("id") UUID id);

    @Modifying
    @Transactional
    @Query("update PullRequestEntity p set p.githubCommentId = :commentId where p.id = :id")
    int updateGithubCommentId(@Param("id") UUID id, @Param("commentId") long commentId);

    /**
     * Page of PRs for a repo, paginated + sorted by the caller.
     * Used by {@code GET /api/repos/{id}/prs}.
     */
    Page<PullRequestEntity> findAllByRepo(Repository repo, Pageable pageable);
}