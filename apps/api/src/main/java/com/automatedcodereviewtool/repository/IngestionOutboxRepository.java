package com.automatedcodereviewtool.repository;

import com.automatedcodereviewtool.entity.IngestionOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface IngestionOutboxRepository extends JpaRepository<IngestionOutbox, UUID> {

    List<IngestionOutbox> findByStatusOrderByAvailableAtAsc(String status);

    List<IngestionOutbox> findByStatusAndAttemptCountLessThanOrderByAvailableAtAsc(
            String status, int maxAttempts);

    @Query(value = "SELECT * FROM ml.ingestion_outbox WHERE status = :status AND attempt_count < :maxAttempts ORDER BY available_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<IngestionOutbox> claimNextPendingBatch(@Param("status") String status, @Param("maxAttempts") int maxAttempts, @Param("batchSize") int batchSize);
}
