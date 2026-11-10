package com.automatedcodereviewtool.repository;

import com.automatedcodereviewtool.entity.ReviewPublication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Publication queue (V22). Every completion write matches the attempt that
 * claimed the row, like {@link ProcessedWebhookRepository}: a publisher
 * whose lease expired and was re-claimed cannot overwrite the newer attempt.
 */
public interface ReviewPublicationRepository extends JpaRepository<ReviewPublication, UUID> {

    Optional<ReviewPublication> findByDeliveryId(String deliveryId);

    List<ReviewPublication> findAllByDeliveryIdIn(Collection<String> deliveryIds);

    /**
     * Compare-and-set lease. A row is claimable when it is due, has attempts
     * left, holds no live lease, and no other publication of the same PR
     * holds one: publications of one PR run one at a time, so the newest
     * head always writes the sticky comment last. Callers hold the PR row
     * lock while claiming, which makes the NOT EXISTS check race-free.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE review_publications
               SET attempt_count = attempt_count + 1, processing_started_at = NOW(), updated_at = NOW()
             WHERE id = :id
               AND status = 'pending'
               AND available_at <= NOW()
               AND attempt_count < max_attempts
               AND attempt_count = :expectedAttempt
               AND (processing_started_at IS NULL OR processing_started_at < :staleBefore)
               AND NOT EXISTS (
                   SELECT 1 FROM review_publications other
                    WHERE other.pr_id = review_publications.pr_id
                      AND other.id <> review_publications.id
                      AND other.status = 'pending'
                      AND other.processing_started_at >= :staleBefore)
            """, nativeQuery = true)
    int claim(@Param("id") UUID id,
              @Param("expectedAttempt") int expectedAttempt,
              @Param("staleBefore") Instant staleBefore);

    /** Never revive an expired lease: an old worker must stop before writing to GitHub. */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE review_publications
               SET processing_started_at = NOW(), updated_at = NOW()
             WHERE id = :id AND status = 'pending' AND attempt_count = :claimedAttempt
               AND processing_started_at >= :staleBefore
            """, nativeQuery = true)
    int renewLease(@Param("id") UUID id, @Param("claimedAttempt") int claimedAttempt,
                   @Param("staleBefore") Instant staleBefore);

    /** Ids as text: H2 hands native UUID columns back as bytes, which Spring Data cannot convert. */
    @Query(value = """
            SELECT CAST(id AS VARCHAR(36))
              FROM review_publications
             WHERE status = 'pending'
               AND available_at <= NOW()
               AND attempt_count < max_attempts
               AND (processing_started_at IS NULL OR processing_started_at < :staleBefore)
             ORDER BY available_at, created_at
             LIMIT :batchSize
            """, nativeQuery = true)
    List<String> findDueIds(@Param("staleBefore") Instant staleBefore, @Param("batchSize") int batchSize);

    /** Rows out of attempts become {@code failed} once no lease is live on them. */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE review_publications
               SET status = 'failed', processing_started_at = NULL, updated_at = NOW(),
                   last_error = COALESCE(last_error, 'Maximum publication attempts exhausted')
             WHERE status = 'pending'
               AND attempt_count >= max_attempts
               AND (processing_started_at IS NULL OR processing_started_at < :staleBefore)
            """, nativeQuery = true)
    int failExhausted(@Param("staleBefore") Instant staleBefore);

    /**
     * Older heads of a PR that are waiting for publication can never be
     * published once a newer head is reviewed. Rows under a live lease are
     * left to their publisher, which re-checks the head itself.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE review_publications
               SET status = 'superseded', processing_started_at = NULL, updated_at = NOW(),
                   last_error = 'A newer head of the pull request was reviewed'
             WHERE pr_id = :prId
               AND head_sha <> :headSha
               AND status = 'pending'
               AND (processing_started_at IS NULL OR processing_started_at < :staleBefore)
            """, nativeQuery = true)
    int supersedeOtherHeads(@Param("prId") UUID prId,
                            @Param("headSha") String headSha,
                            @Param("staleBefore") Instant staleBefore);

    /** Durably records the summary comment as soon as it exists, so any retry edits it. */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE review_publications
               SET comment_id = :commentId, comment_url = :commentUrl, updated_at = NOW()
             WHERE id = :id AND status = 'pending' AND attempt_count = :claimedAttempt
            """, nativeQuery = true)
    int recordComment(@Param("id") UUID id,
                      @Param("claimedAttempt") int claimedAttempt,
                      @Param("commentId") long commentId,
                      @Param("commentUrl") String commentUrl);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE review_publications
               SET status = 'published', processing_started_at = NULL, updated_at = NOW(),
                   published_at = NOW(), published_head_sha = head_sha,
                   review_id = :reviewId, last_error = :message
             WHERE id = :id AND status = 'pending' AND attempt_count = :claimedAttempt
            """, nativeQuery = true)
    int markPublished(@Param("id") UUID id,
                      @Param("claimedAttempt") int claimedAttempt,
                      @Param("reviewId") Long reviewId,
                      @Param("message") String message);

    /** {@code failed}, {@code superseded} or {@code skipped}. */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE review_publications
               SET status = :status, processing_started_at = NULL, updated_at = NOW(), last_error = :message
             WHERE id = :id AND status = 'pending' AND attempt_count = :claimedAttempt
            """, nativeQuery = true)
    int markTerminal(@Param("id") UUID id,
                     @Param("claimedAttempt") int claimedAttempt,
                     @Param("status") String status,
                     @Param("message") String message);

    /** Release the lease and retry at {@code retryAt}, or fail once attempts are used up. */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE review_publications
               SET status = CASE WHEN attempt_count >= max_attempts THEN 'failed' ELSE 'pending' END,
                   processing_started_at = NULL, available_at = :retryAt,
                   updated_at = NOW(), last_error = :message
             WHERE id = :id AND status = 'pending' AND attempt_count = :claimedAttempt
            """, nativeQuery = true)
    int markRetry(@Param("id") UUID id,
                  @Param("claimedAttempt") int claimedAttempt,
                  @Param("retryAt") Instant retryAt,
                  @Param("message") String message);
}
