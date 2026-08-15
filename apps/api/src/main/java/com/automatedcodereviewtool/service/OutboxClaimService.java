package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.entity.IngestionOutbox;
import com.automatedcodereviewtool.repository.IngestionOutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/** Short transaction boundaries for outbox claim and state transitions. */
@Service
public class OutboxClaimService {

    private final IngestionOutboxRepository repository;

    public OutboxClaimService(IngestionOutboxRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public List<IngestionOutbox> claim(String workerId, int batchSize, OffsetDateTime staleBefore) {
        repository.deadLetterExhausted();
        repository.recoverStaleProcessing(staleBefore);
        repository.deadLetterExhausted();
        return repository.claimEligibleBatch(workerId, batchSize);
    }

    @Transactional
    public boolean complete(java.util.UUID id, String workerId) {
        return repository.markCompleted(id, workerId) == 1;
    }

    @Transactional
    public boolean fail(java.util.UUID id, String workerId, String error, OffsetDateTime availableAt) {
        return repository.markFailed(id, workerId, error, availableAt) == 1;
    }
}
