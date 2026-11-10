package com.automatedcodereviewtool.repository;

import com.automatedcodereviewtool.entity.ProcessedWebhook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProcessedWebhookRepository extends JpaRepository<ProcessedWebhook, String> {

    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO processed_webhooks (
                delivery_id, repo_id, github_pr_number, head_sha, action,
                status, attempt_count, max_attempts, available_at, processed_at, updated_at
            ) VALUES (
                :deliveryId, :repoId, :prNumber, :headSha, :action,
                'received', 0, 5, NOW(), NOW(), NOW()
            )
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertReceived(@Param("deliveryId") String deliveryId,
                       @Param("repoId") java.util.UUID repoId,
                       @Param("prNumber") int prNumber,
                       @Param("headSha") String headSha,
                       @Param("action") String action);

    /**
     * Compare-and-set claim. The caller passes the {@code attempt_count} it
     * read; on success the row's new attempt number ({@code expectedAttempt + 1})
     * is the fencing token for {@link #markTerminal} and {@link #markFailed}.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE processed_webhooks
               SET status = 'processing', attempt_count = attempt_count + 1,
                   processing_started_at = NOW(), updated_at = NOW(), last_error = NULL
             WHERE delivery_id = :deliveryId
               AND status IN ('received', 'failed')
               AND available_at <= NOW()
               AND attempt_count < max_attempts
               AND attempt_count = :expectedAttempt
            """, nativeQuery = true)
    int claim(@Param("deliveryId") String deliveryId,
              @Param("expectedAttempt") int expectedAttempt);

    @Query(value = """
            SELECT delivery_id
              FROM processed_webhooks
             WHERE status IN ('received', 'failed')
               AND available_at <= NOW()
               AND attempt_count < max_attempts
             ORDER BY available_at, processed_at
             LIMIT :batchSize
            """, nativeQuery = true)
    List<String> findEligibleDeliveryIds(@Param("batchSize") int batchSize);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE processed_webhooks
               SET status = 'failed', processing_started_at = NULL,
                   available_at = NOW(), updated_at = NOW(),
                   last_error = COALESCE(last_error, 'Processing lease expired')
             WHERE status = 'processing'
               AND processing_started_at < :staleBefore
               AND attempt_count < max_attempts
            """, nativeQuery = true)
    int recoverStale(@Param("staleBefore") Instant staleBefore);

    /**
     * Dead-letter deliveries that used up their attempts. A row still in
     * {@code processing} is only touched once its lease has expired, so the
     * final attempt is never dead-lettered while it is running.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE processed_webhooks
               SET status = 'dead_letter', completed_at = NOW(), processing_started_at = NULL,
                   updated_at = NOW(), last_error = COALESCE(last_error, 'Maximum attempts exhausted')
             WHERE attempt_count >= max_attempts
               AND (status IN ('received', 'failed')
                    OR (status = 'processing' AND processing_started_at < :staleBefore))
            """, nativeQuery = true)
    int deadLetterExhausted(@Param("staleBefore") Instant staleBefore);

    /**
     * Finish the attempt identified by {@code claimedAttempt}. A worker whose
     * lease expired and whose row was re-claimed matches nothing, so it can't
     * overwrite the newer attempt.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE processed_webhooks
               SET status = :status, completed_at = NOW(), processing_started_at = NULL,
                   updated_at = NOW(), last_error = :message
             WHERE delivery_id = :deliveryId AND status = 'processing'
               AND attempt_count = :claimedAttempt
            """, nativeQuery = true)
    int markTerminal(@Param("deliveryId") String deliveryId,
                     @Param("claimedAttempt") int claimedAttempt,
                     @Param("status") String status,
                     @Param("message") String message);

    /** Fenced like {@link #markTerminal}. */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE processed_webhooks
               SET status = CASE WHEN attempt_count >= max_attempts THEN 'dead_letter' ELSE 'failed' END,
                   completed_at = CASE WHEN attempt_count >= max_attempts THEN NOW() ELSE NULL END,
                   processing_started_at = NULL, available_at = :retryAt,
                   updated_at = NOW(), last_error = :message
             WHERE delivery_id = :deliveryId AND status = 'processing'
               AND attempt_count = :claimedAttempt
            """, nativeQuery = true)
    int markFailed(@Param("deliveryId") String deliveryId,
                   @Param("claimedAttempt") int claimedAttempt,
                   @Param("retryAt") Instant retryAt,
                   @Param("message") String message);

    /**
     * Lock the delivery row and return the attempt that currently holds its
     * processing lease (empty when it is not being processed). A review
     * transaction commits only while it still owns that attempt.
     */
    @Query(value = """
            SELECT attempt_count
              FROM processed_webhooks
             WHERE delivery_id = :deliveryId AND status = 'processing'
               FOR UPDATE
            """, nativeQuery = true)
    Optional<Integer> lockProcessingAttempt(@Param("deliveryId") String deliveryId);

    /** Review runs of one PR, newest first ({@code GET /api/reviews/{prId}/runs}). */
    List<ProcessedWebhook> findTop50ByRepoIdAndGithubPrNumberOrderByProcessedAtDesc(UUID repoId,
                                                                                   Integer githubPrNumber);
}
