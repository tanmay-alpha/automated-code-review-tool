package com.automatedcodereviewtool.repository;

import com.automatedcodereviewtool.entity.IngestionOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

@Repository
public interface IngestionOutboxRepository extends JpaRepository<IngestionOutbox, UUID> {

    Optional<IngestionOutbox> findByDeduplicationKey(String deduplicationKey);

    /**
     * Atomically selects eligible rows, locks them with SKIP LOCKED, and moves
     * them to processing before returning. This method must run in a real
     * transaction; {@code OutboxClaimService} owns that boundary.
     */
    @Query(value = """
            WITH eligible AS (
                SELECT id
                  FROM ml.ingestion_outbox
                 WHERE status = 'pending'
                   AND available_at <= NOW()
                   AND attempt_count < max_attempts
                 ORDER BY available_at, created_at
                 FOR UPDATE SKIP LOCKED
                 LIMIT :batchSize
            ), claimed AS (
                UPDATE ml.ingestion_outbox o
                   SET status = 'processing',
                       attempt_count = o.attempt_count + 1,
                       locked_at = NOW(),
                       locked_by = :workerId,
                       updated_at = NOW()
                  FROM eligible e
                 WHERE o.id = e.id
                RETURNING o.*
            )
            SELECT * FROM claimed ORDER BY available_at, created_at
            """, nativeQuery = true)
    List<IngestionOutbox> claimEligibleBatch(@Param("workerId") String workerId,
                                             @Param("batchSize") int batchSize);

    @Modifying
    @Query(value = """
            UPDATE ml.ingestion_outbox
               SET status = 'dead_letter', dead_lettered_at = NOW(),
                   locked_at = NULL, locked_by = NULL, updated_at = NOW(),
                   last_error = COALESCE(last_error, 'Maximum attempts exhausted')
             WHERE status IN ('pending', 'processing', 'failed')
               AND attempt_count >= max_attempts
            """, nativeQuery = true)
    int deadLetterExhausted();

    @Modifying
    @Query(value = """
            UPDATE ml.ingestion_outbox
               SET status = 'pending', locked_at = NULL, locked_by = NULL,
                   available_at = NOW(), updated_at = NOW(),
                   last_error = COALESCE(last_error, 'Processing lease expired')
             WHERE status = 'processing'
               AND locked_at < :staleBefore
               AND attempt_count < max_attempts
            """, nativeQuery = true)
    int recoverStaleProcessing(@Param("staleBefore") OffsetDateTime staleBefore);

    @Modifying
    @Query(value = """
            UPDATE ml.ingestion_outbox
               SET status = 'completed', processed_at = NOW(), last_error = NULL,
                   locked_at = NULL, locked_by = NULL, updated_at = NOW()
             WHERE id = :id AND status = 'processing' AND locked_by = :workerId
            """, nativeQuery = true)
    int markCompleted(@Param("id") UUID id, @Param("workerId") String workerId);

    @Modifying
    @Query(value = """
            UPDATE ml.ingestion_outbox
               SET status = CASE WHEN attempt_count >= max_attempts THEN 'dead_letter' ELSE 'pending' END,
                   available_at = CASE WHEN attempt_count >= max_attempts THEN available_at ELSE :availableAt END,
                   dead_lettered_at = CASE WHEN attempt_count >= max_attempts THEN NOW() ELSE NULL END,
                   last_error = :lastError, locked_at = NULL, locked_by = NULL, updated_at = NOW()
             WHERE id = :id AND status = 'processing' AND locked_by = :workerId
            """, nativeQuery = true)
    int markFailed(@Param("id") UUID id,
                   @Param("workerId") String workerId,
                   @Param("lastError") String lastError,
                   @Param("availableAt") OffsetDateTime availableAt);
}
