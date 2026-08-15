package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.entity.CodeSample;
import com.automatedcodereviewtool.entity.Finding;
import com.automatedcodereviewtool.entity.IngestionOutbox;
import com.automatedcodereviewtool.entity.PredictionEvent;
import com.automatedcodereviewtool.repository.CodeSampleRepository;
import com.automatedcodereviewtool.repository.FindingRepository;
import com.automatedcodereviewtool.repository.PredictionEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Lease-based consumer for durable, redacted ML-ingestion work. */
@Service
public class OutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);
    private static final int BATCH_SIZE = 10;
    private static final Duration LEASE = Duration.ofMinutes(5);

    private final OutboxClaimService claimService;
    private final CodeSampleRepository codeSampleRepository;
    private final FindingRepository findingRepository;
    private final PredictionEventRepository predictionEventRepository;
    private final ObjectMapper objectMapper;
    private final SecretRedactor secretRedactor;
    private final String workerId;

    public OutboxProcessor(OutboxClaimService claimService,
                           CodeSampleRepository codeSampleRepository,
                           FindingRepository findingRepository,
                           PredictionEventRepository predictionEventRepository,
                           ObjectMapper objectMapper,
                           SecretRedactor secretRedactor) {
        this.claimService = claimService;
        this.codeSampleRepository = codeSampleRepository;
        this.findingRepository = findingRepository;
        this.predictionEventRepository = predictionEventRepository;
        this.objectMapper = objectMapper;
        this.secretRedactor = secretRedactor;
        this.workerId = createWorkerId();
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-delay-ms:10000}")
    public void processPendingEvents() {
        List<IngestionOutbox> claimed = claimService.claim(
                workerId, BATCH_SIZE, OffsetDateTime.now().minus(LEASE));
        for (IngestionOutbox event : claimed) {
            processClaimedEvent(event);
        }
    }

    boolean processClaimedEvent(IngestionOutbox outbox) {
        try {
            JsonNode payload = objectMapper.readTree(outbox.getPayload());
            UUID prId = requiredUuid(payload, "pullRequestId");
            requiredUuid(payload, "repositoryId");
            requiredText(payload, "headSha");
            List<UUID> sampleIds = uuidList(payload.get("codeSampleIds"));
            List<CodeSample> samples = codeSampleRepository.findAllById(sampleIds);
            if (samples.size() != sampleIds.size()) {
                throw new IllegalStateException("Referenced code sample is missing");
            }

            linkFindings(payload.get("findingIds"), samples);
            linkPredictionEvents(payload.get("predictionEventIds"), samples);

            if (!claimService.complete(outbox.getId(), workerId)) {
                log.warn("Lost outbox lease before completing event {}", outbox.getId());
                return false;
            }
            return true;
        } catch (Exception ex) {
            String safeError = safeError(ex);
            int attempt = outbox.getAttemptCount() == null ? 1 : outbox.getAttemptCount();
            long baseSeconds = Math.min(600L, 10L << Math.min(attempt, 5));
            long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, baseSeconds / 4L));
            OffsetDateTime retryAt = OffsetDateTime.now().plusSeconds(baseSeconds + jitter);
            claimService.fail(outbox.getId(), workerId, safeError, retryAt);
            log.error("Outbox event {} failed on attempt {}: {}", outbox.getId(), attempt, safeError);
            return false;
        }
    }

    private void linkFindings(JsonNode ids, List<CodeSample> samples) {
        if (ids == null || !ids.isArray()) return;
        for (JsonNode node : ids) {
            findingRepository.findById(UUID.fromString(node.asText())).ifPresent(finding -> {
                CodeSample sample = matchingSample(samples, finding.getFilePath(), finding.getLineStart(), finding.getLineEnd());
                if (sample != null) {
                    finding.setCodeSampleId(sample.getId());
                    findingRepository.save(finding);
                }
            });
        }
    }

    private void linkPredictionEvents(JsonNode ids, List<CodeSample> samples) {
        if (ids == null || !ids.isArray()) return;
        for (JsonNode node : ids) {
            predictionEventRepository.findById(UUID.fromString(node.asText())).ifPresent(event -> {
                CodeSample sample = matchingSample(
                        samples, event.getFilePath(), event.getReportedLineStart(), event.getReportedLineEnd());
                if (sample != null) {
                    event.setCodeSampleId(sample.getId());
                    predictionEventRepository.save(event);
                }
            });
        }
    }

    static CodeSample matchingSample(List<CodeSample> samples, String filePath,
                                     Integer lineStart, Integer lineEnd) {
        if (filePath == null || lineStart == null) return null;
        int end = lineEnd == null ? lineStart : lineEnd;
        for (CodeSample sample : samples) {
            int sampleEnd = sample.getNewStart() + Math.max(sample.getNewCount(), 1) - 1;
            if (filePath.equals(sample.getFilePath())
                    && sample.getNewStart() <= lineStart
                    && end <= sampleEnd) {
                return sample;
            }
        }
        return null;
    }

    private static UUID requiredUuid(JsonNode payload, String field) {
        return UUID.fromString(requiredText(payload, field));
    }

    private static List<UUID> uuidList(JsonNode values) {
        if (values == null || !values.isArray()) {
            throw new IllegalArgumentException("Missing outbox field: codeSampleIds");
        }
        java.util.ArrayList<UUID> ids = new java.util.ArrayList<>();
        values.forEach(value -> ids.add(UUID.fromString(value.asText())));
        return ids;
    }

    private static String requiredText(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Missing outbox field: " + field);
        }
        return value.asText();
    }

    private String safeError(Exception ex) {
        String value = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        value = secretRedactor.redact(value);
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }

    private static String createWorkerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            host = "unknown-host";
        }
        String value = host + ":" + UUID.randomUUID();
        return value.length() <= 100 ? value : value.substring(0, 100);
    }
}
