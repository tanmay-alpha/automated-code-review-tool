package com.automatedcodereviewtool.repository;

import com.automatedcodereviewtool.entity.IngestionOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface IngestionOutboxRepository extends JpaRepository<IngestionOutbox, UUID> {

    List<IngestionOutbox> findByStatusOrderByAvailableAtAsc(String status);

    List<IngestionOutbox> findByStatusAndAttemptCountLessThanOrderByAvailableAtAsc(
            String status, int maxAttempts);
}
