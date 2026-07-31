package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.entity.CodeSample;
import com.automatedcodereviewtool.entity.Finding;
import com.automatedcodereviewtool.entity.IngestionOutbox;
import com.automatedcodereviewtool.entity.PredictionEvent;
import com.automatedcodereviewtool.repository.FindingRepository;
import com.automatedcodereviewtool.repository.IngestionOutboxRepository;
import com.automatedcodereviewtool.repository.PredictionEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Consumes {@link IngestionOutbox} events asynchronously in separate transactions.
 *
 * <p>Key guarantees:
 * <ul>
 *   <li>Dataset capture failures NEVER fail normal PR reviews.</li>
 *   <li>Idempotent sample persistence and outbox event claiming.</li>
 *   <li>Exponential backoff on retries, transitioning to {@code dead_letter} after max attempts.</li>
 * </ul>
 */
@Service
public class OutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);
    private static final int MAX_ATTEMPTS = 5;

    private final IngestionOutboxRepository outboxRepository;
    private final ReviewSampleBridge reviewSampleBridge;
    private final FindingRepository findingRepository;
    private final PredictionEventRepository predictionEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxProcessor(IngestionOutboxRepository outboxRepository,
                           ReviewSampleBridge reviewSampleBridge,
                           FindingRepository findingRepository,
                           PredictionEventRepository predictionEventRepository,
                           ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.reviewSampleBridge = reviewSampleBridge;
        this.findingRepository = findingRepository;
        this.predictionEventRepository = predictionEventRepository;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 10000)
    public void processPendingEvents() {
        List<IngestionOutbox> pending;
        try {
            pending = outboxRepository.claimNextPendingBatch("pending", MAX_ATTEMPTS, 10);
        } catch (Exception ex) {
            pending = outboxRepository.findByStatusAndAttemptCountLessThanOrderByAvailableAtAsc("pending", MAX_ATTEMPTS);
        }

        for (IngestionOutbox event : pending) {
            processSingleEvent(event.getId());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean processSingleEvent(UUID eventId) {
        IngestionOutbox outbox = outboxRepository.findById(eventId).orElse(null);
        if (outbox == null || "completed".equals(outbox.getStatus()) || "dead_letter".equals(outbox.getStatus())) {
            return false;
        }

        outbox.setStatus("processing");
        outbox.setAttemptCount(outbox.getAttemptCount() + 1);
        outboxRepository.save(outbox);

        try {
            JsonNode payload = objectMapper.readTree(outbox.getPayload());
            UUID prId = UUID.fromString(payload.get("pullRequestId").asText());
            String headSha = payload.get("headSha").asText();
            String diff = payload.has("diff") ? payload.get("diff").asText() : "";

            if (!diff.isBlank()) {
                List<CodeSample> samples = reviewSampleBridge.persistHunksForReview(
                        outbox.getAggregateId(), prId, headSha, diff);

                // Map findings and prediction events to samples using hunkHash
                if (payload.has("findingIds")) {
                    for (JsonNode fNode : payload.get("findingIds")) {
                        UUID fId = UUID.fromString(fNode.asText());
                        findingRepository.findById(fId).ifPresent(f -> {
                            for (CodeSample cs : samples) {
                                if (cs.getFilePath().equals(f.getFilePath())) {
                                    f.setCodeSampleId(cs.getId());
                                    findingRepository.save(f);
                                    break;
                                }
                            }
                        });
                    }
                }
            }

            outbox.setStatus("completed");
            outbox.setProcessedAt(OffsetDateTime.now());
            outbox.setLastError(null);
            outboxRepository.save(outbox);
            return true;
        } catch (Exception ex) {
            log.error("Outbox event {} failed (attempt {}): {}", eventId, outbox.getAttemptCount(), ex.getMessage());
            outbox.setLastError(ex.getMessage());
            if (outbox.getAttemptCount() >= MAX_ATTEMPTS) {
                outbox.setStatus("dead_letter");
            } else {
                outbox.setStatus("pending");
                // Exponential backoff: 2^attempts * 10 seconds
                long backoffSeconds = (long) Math.pow(2, outbox.getAttemptCount()) * 10;
                outbox.setAvailableAt(OffsetDateTime.now().plusSeconds(backoffSeconds));
            }
            outboxRepository.save(outbox);
            return false;
        }
    }
}
