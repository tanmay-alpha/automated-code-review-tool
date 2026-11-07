package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.diff.HunkParser;
import com.automatedcodereviewtool.dto.MlFinding;
import com.automatedcodereviewtool.dto.MlReviewResponse;
import com.automatedcodereviewtool.dto.ReviewResult;
import com.automatedcodereviewtool.entity.AntiPattern;
import com.automatedcodereviewtool.entity.CodeSample;
import com.automatedcodereviewtool.entity.Finding;
import com.automatedcodereviewtool.entity.IngestionOutbox;
import com.automatedcodereviewtool.entity.PredictionEvent;
import com.automatedcodereviewtool.entity.PullRequestEntity;
import com.automatedcodereviewtool.entity.QualityMetric;
import com.automatedcodereviewtool.entity.Repository;
import com.automatedcodereviewtool.exception.InvalidDiffException;
import com.automatedcodereviewtool.exception.MlWorkerException;
import com.automatedcodereviewtool.repository.FindingRepository;
import com.automatedcodereviewtool.repository.IngestionOutboxRepository;
import com.automatedcodereviewtool.repository.PredictionEventRepository;
import com.automatedcodereviewtool.repository.PullRequestRepository;
import com.automatedcodereviewtool.repository.QualityMetricRepository;
import com.automatedcodereviewtool.repository.RepositoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Orchestrates a single review: call ML worker → persist findings →
 * record rejected predictions → insert outbox event → update PR state.
 *
 * <p>Dataset capture is decoupled via the outbox: the review transaction
 * inserts an outbox event; a background worker consumes it. This ensures
 * dataset capture failure cannot affect normal PR review completion.</p>
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private static final String OUTBOX_EVENT_TYPE = "DATASET_CAPTURE_REQUESTED";

    /**
     * Largest diff (in chars) sent to the ML worker. Bigger diffs fail the
     * PR up front with a clear message instead of a worker 4xx.
     */
    static final int MAX_DIFF_CHARS = 200_000;
    /** Upper bound for an error message stored on the PR row. */
    private static final int MAX_ERROR_CHARS = 2000;
    private static final java.util.Set<String> SEVERITIES = java.util.Set.of("critical", "major", "minor");

    private final MlWorkerService mlWorkerService;
    private final FindingRepository findingRepository;
    private final PullRequestRepository pullRequestRepository;
    private final QualityMetricRepository qualityMetricRepository;
    private final RepositoryRepository repositoryRepository;
    private final ReviewSampleBridge reviewSampleBridge;
    private final IngestionOutboxRepository outboxRepository;
    private final PredictionEventRepository predictionEventRepository;
    private final ObjectMapper objectMapper;
    private final SecretRedactor secretRedactor;
    private final TaxonomyService taxonomyService;
    private final TransactionTemplate transactionTemplate;

    public ReviewService(MlWorkerService mlWorkerService,
                         FindingRepository findingRepository,
                         PullRequestRepository pullRequestRepository,
                         QualityMetricRepository qualityMetricRepository,
                         RepositoryRepository repositoryRepository,
                         ReviewSampleBridge reviewSampleBridge,
                         IngestionOutboxRepository outboxRepository,
                         PredictionEventRepository predictionEventRepository,
                         ObjectMapper objectMapper,
                         SecretRedactor secretRedactor,
                         TaxonomyService taxonomyService,
                         PlatformTransactionManager transactionManager) {
        this.mlWorkerService = mlWorkerService;
        this.findingRepository = findingRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.qualityMetricRepository = qualityMetricRepository;
        this.repositoryRepository = repositoryRepository;
        this.reviewSampleBridge = reviewSampleBridge;
        this.outboxRepository = outboxRepository;
        this.predictionEventRepository = predictionEventRepository;
        this.objectMapper = objectMapper;
        this.secretRedactor = secretRedactor;
        this.taxonomyService = taxonomyService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Full review pipeline for a PR.
     *
     * <p>Every worker prediction is recorded as a prediction event. Those
     * that can be located on a new-side line of the diff become findings;
     * the rest are stored as rejected events instead of being dropped.</p>
     *
     * <p>The worker call runs without a database transaction. Persistence of
     * findings, redacted samples, and their outbox references is atomic.</p>
     */
    public ReviewResult orchestrateReview(PullRequestEntity pr, String diff) {
        if (diff == null || diff.isBlank()) {
            diff = "";
        }

        if (diff.length() > MAX_DIFF_CHARS) {
            String message = "Diff too large to review (" + diff.length()
                    + " characters; limit " + MAX_DIFF_CHARS + ")";
            log.warn("PR #{}: {}", pr.getGithubPrNumber(), message);
            return failReview(pr, message, false);
        }

        String language = MlWorkerService.detectLanguage(diff);
        log.info("Reviewing PR #{} — detected language: {}", pr.getGithubPrNumber(), language);

        MlReviewResponse mlResponse;
        try {
            mlResponse = mlWorkerService.review(diff, language);
        } catch (MlWorkerException | InvalidDiffException ex) {
            // InvalidDiffException: the worker rejected the input with a 4xx —
            // retrying the same diff cannot help.
            String safeError = safeError(ex.getMessage(), "ML worker call failed");
            log.error("ML worker failed for PR #{}: {}", pr.getGithubPrNumber(), safeError);
            return failReview(pr, safeError, !(ex instanceof InvalidDiffException));
        }

        Map<String, Map<Integer, String>> newLines = newSideLinesByFile(diff);
        String reviewDiff = diff;

        try {
            ReviewResult persisted = transactionTemplate.execute(ignored ->
                    persistReview(pr, reviewDiff, mlResponse, newLines));
            return Objects.requireNonNull(persisted, "review transaction returned no result");
        } catch (RuntimeException ex) {
            String safeError = safeError(ex.getMessage(), "review persistence failed");
            // A constraint violation is deterministic for this worker response;
            // retrying would only fail the same way.
            boolean retryable = !isConstraintViolation(ex);
            log.error("Review persistence failed for PR #{} (retryable={}): {}",
                    pr.getGithubPrNumber(), retryable, safeError);
            return failReview(pr, safeError, retryable);
        }
    }

    private ReviewResult failReview(PullRequestEntity pr, String safeError, boolean retryable) {
        pr.setStatus(WebhookService.STATUS_FAILED);
        pr.setErrorMessage(safeError);
        pullRequestRepository.save(pr);
        return ReviewResult.failed(safeError, retryable);
    }

    /** Redacted and bounded: this text is stored on the PR row, logged, and returned by the API. */
    private String safeError(String message, String fallback) {
        String safe = secretRedactor.redact(message == null || message.isBlank() ? fallback : message);
        return safe.length() <= MAX_ERROR_CHARS ? safe : safe.substring(0, MAX_ERROR_CHARS);
    }

    private static boolean isConstraintViolation(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof DataIntegrityViolationException
                    || t instanceof org.hibernate.exception.ConstraintViolationException
                    || t instanceof java.sql.SQLIntegrityConstraintViolationException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private ReviewResult persistReview(PullRequestEntity pr,
                                       String diff,
                                       MlReviewResponse mlResponse,
                                       Map<String, Map<Integer, String>> newLines) {
        // A re-review (new head SHA) replaces the previous findings.
        findingRepository.deleteAllByPullRequestId(pr.getId());

        List<Finding> saved = new ArrayList<>();
        List<PredictionEvent> predictionEvents = new ArrayList<>();
        int rejected = 0;
        BigDecimal score = MlWorkerService.computeQualityScore(mlResponse);

        if (mlResponse.findings() != null) {
            for (MlFinding ml : mlResponse.findings()) {
                AntiPattern taxonomy = taxonomyService.find(ml.antiPattern()).orElse(null);
                PredictionEvent event = buildPredictionEvent(pr, ml, taxonomy, mlResponse);
                String resolvedPath = resolveFilePath(ml, newLines);
                String rejection = classifyRejection(ml, taxonomy, resolvedPath);
                if (rejection != null) {
                    event.setStatus(rejection);
                    event.setRejectionReason(rejection.replace("rejected_", ""));
                    rejected++;
                    log.warn("Rejected prediction: antiPattern={} file={} line={} reason={}",
                            ml.antiPattern(), ml.filePath(), ml.lineStart(), rejection);
                } else {
                    event.setStatus("persisted");
                    // Category and severity come from the canonical taxonomy row, so a
                    // worker value outside the findings CHECK constraints cannot fail
                    // (and roll back) the whole review.
                    Finding f = Finding.builder()
                            .pullRequest(pr)
                            .filePath(resolvedPath)
                            .lineStart(ml.lineStart())
                            .lineEnd(ml.lineEnd())
                            .antiPattern(taxonomy.getId())
                            .category(taxonomy.getCategory())
                            .severity(taxonomy.getDefaultSeverity())
                            .confidence(clampConfidence(ml.confidence()))
                            .explanation(ml.explanation())
                            .codeSnippet(extractCodeSnippet(ml, newLines.get(resolvedPath)))
                            .engine(mlResponse.engine())
                            .modelVersion(mlResponse.modelVersion())
                            .taxonomyVersion(mlResponse.taxonomyVersion())
                            .build();
                    saved.add(findingRepository.save(f));
                }
                predictionEvents.add(predictionEventRepository.save(event));
            }
        }

        // Persist redacted samples before the outbox. The event contains only
        // safe row references, never source text or a full diff.
        List<CodeSample> samples = reviewSampleBridge.persistHunksForReview(
                pr.getRepo().getId(), pr.getId(), pr.getHeadSha(), diff);

        // One outbox row per (PR, head). A re-review of the same head deleted the
        // findings the old payload referenced, so the row is rewritten with the
        // new references and re-queued; a worker still holding the old lease
        // loses it (locked_by is cleared) and its completion is discarded.
        String deduplicationKey = OUTBOX_EVENT_TYPE + ":" + pr.getId() + ":" + pr.getHeadSha();
        OffsetDateTime now = OffsetDateTime.now();
        IngestionOutbox outbox = outboxRepository.findByDeduplicationKey(deduplicationKey)
                .orElseGet(() -> {
                    IngestionOutbox created = new IngestionOutbox();
                    created.setEventType(OUTBOX_EVENT_TYPE);
                    created.setAggregateType("pull_request");
                    created.setAggregateId(pr.getId());
                    created.setDeduplicationKey(deduplicationKey);
                    created.setMaxAttempts(5);
                    created.setCreatedAt(now);
                    return created;
                });
        outbox.setPayload(buildOutboxPayload(pr, saved, predictionEvents, samples));
        outbox.setStatus("pending");
        outbox.setAttemptCount(0);
        outbox.setAvailableAt(now);
        outbox.setLockedAt(null);
        outbox.setLockedBy(null);
        outbox.setProcessedAt(null);
        outbox.setDeadLetteredAt(null);
        outbox.setLastError(null);
        outbox.setUpdatedAt(now);
        IngestionOutbox durableEvent = outboxRepository.save(outbox);
        log.info("Queued outbox event {} for PR #{}", durableEvent.getId(), pr.getGithubPrNumber());

        pr.setQualityScore(score);
        pr.setStatus(WebhookService.STATUS_REVIEWED);
        pr.setErrorMessage(null);
        pr.setReviewedAt(Instant.now());
        pullRequestRepository.save(pr);

        updateRepositoryQualityScore(pr.getRepo(), score);
        updateQualityMetric(pr.getRepo(), score, saved);

        log.info("PR #{} reviewed — score={}, findings={}, rejected={}",
                pr.getGithubPrNumber(), score, saved.size(), rejected);
        return ReviewResult.of(pr, saved, score, mlResponse.engine());
    }

    // -----------------------------------------------------------------
    // Rejected prediction classification
    // -----------------------------------------------------------------

    private static String classifyRejection(MlFinding ml, AntiPattern taxonomy, String resolvedPath) {
        if (taxonomy == null) {
            return "rejected_unknown_taxonomy";
        }
        BigDecimal conf = ml.confidence();
        if (conf != null && (conf.compareTo(BigDecimal.ZERO) < 0 || conf.compareTo(BigDecimal.ONE) > 0)) {
            return "rejected_invalid_confidence";
        }
        if (resolvedPath == null) {
            return "rejected_unmapped_file";
        }
        return null;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static BigDecimal clampConfidence(BigDecimal confidence) {
        return confidence == null ? BigDecimal.ZERO
                : confidence.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    // -----------------------------------------------------------------
    // Prediction event builder
    // -----------------------------------------------------------------

    /**
     * Known patterns take category/severity from the taxonomy row. Unknown
     * ones keep the worker's values, bounded to the column sizes and the
     * severity CHECK constraint, so the rejected event itself always persists.
     */
    private PredictionEvent buildPredictionEvent(PullRequestEntity pr, MlFinding ml,
                                                 AntiPattern taxonomy, MlReviewResponse response) {
        PredictionEvent event = new PredictionEvent();
        event.setPullRequestId(pr.getId());
        event.setFilePath(ml.filePath());
        event.setReportedLineStart(ml.lineStart());
        event.setReportedLineEnd(ml.lineEnd());
        if (taxonomy != null) {
            event.setAntiPatternId(taxonomy.getId());
            event.setCategory(taxonomy.getCategory());
            event.setSeverity(taxonomy.getDefaultSeverity());
        } else {
            event.setAntiPatternId(truncate(ml.antiPattern() == null || ml.antiPattern().isBlank()
                    ? "UNKNOWN" : ml.antiPattern(), 80));
            event.setCategory(truncate(ml.category() == null ? "unknown" : ml.category(), 30));
            String severity = ml.severity() == null ? "minor" : ml.severity().toLowerCase(Locale.ROOT);
            event.setSeverity(SEVERITIES.contains(severity) ? severity : "minor");
        }
        event.setConfidence(clampConfidence(ml.confidence()));
        event.setEngine(response.engine());
        event.setModelVersion(response.modelVersion());
        event.setTaxonomyVersion(response.taxonomyVersion());
        event.setRawMetadata(buildSafeMetadata(response));
        event.setCreatedAt(OffsetDateTime.now());
        return event;
    }

    /**
     * Metadata JSON with no source content — only which detector produced
     * the prediction and how long the worker took.
     */
    private String buildSafeMetadata(MlReviewResponse response) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("engine", response.engine());
        meta.put("model_version", response.modelVersion());
        meta.put("taxonomy_version", response.taxonomyVersion());
        meta.put("processing_time_ms", response.processingTimeMs());
        // Deliberately omit: raw diff, raw source, file content.
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    // -----------------------------------------------------------------
    // Outbox payload
    // -----------------------------------------------------------------

    private String buildOutboxPayload(PullRequestEntity pr,
                                      List<Finding> findings,
                                      List<PredictionEvent> predictionEvents,
                                      List<CodeSample> samples) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pullRequestId", pr.getId().toString());
        payload.put("repositoryId", pr.getRepo().getId().toString());
        payload.put("githubPrNumber", pr.getGithubPrNumber());
        payload.put("headSha", pr.getHeadSha());
        payload.put("codeSampleIds", samples.stream().map(s -> s.getId().toString()).toList());
        payload.put("findingIds", findings.stream().map(f -> f.getId().toString()).toList());
        payload.put("predictionEventIds", predictionEvents.stream().map(e -> e.getId().toString()).toList());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize redacted outbox payload", e);
        }
    }

    // -----------------------------------------------------------------
    // Diff mapping — locate findings on new-side lines of the diff
    // -----------------------------------------------------------------

    /**
     * New-file line number → line text, per file path, for every added or
     * context line in the diff. The ML worker reports findings as
     * ({@code filePath}, new-file {@code lineStart}), so both keys are
     * needed: the same line number usually exists in several files.
     */
    static Map<String, Map<Integer, String>> newSideLinesByFile(String diff) {
        Map<String, Map<Integer, String>> byFile = new LinkedHashMap<>();
        for (HunkParser.FileDiff file : HunkParser.parse(diff).files()) {
            Map<Integer, String> lines = byFile.computeIfAbsent(file.newPath(), k -> new HashMap<>());
            for (HunkParser.FileHunk hunk : file.hunks()) {
                String[] raw = hunk.rawHunk().split("\n", -1);
                int lineNo = hunk.newStart();
                // raw[0] is the "@@ ... @@" header; the rest are body lines.
                for (int i = 1; i < raw.length; i++) {
                    String line = raw[i];
                    if (line.startsWith("-") || line.startsWith("\\")) {
                        continue; // removed line or "\ No newline at end of file"
                    }
                    boolean prefixed = line.startsWith("+") || line.startsWith(" ");
                    lines.put(lineNo++, prefixed ? line.substring(1) : line);
                }
            }
        }
        return byFile;
    }

    /**
     * The file a prediction belongs to, or {@code null} when it cannot be
     * placed on a new-side line of the diff. Without a worker-supplied path
     * the line must match exactly one file.
     */
    static String resolveFilePath(MlFinding ml, Map<String, Map<Integer, String>> newLines) {
        if (ml.lineStart() == null) {
            return null;
        }
        if (ml.filePath() != null) {
            Map<Integer, String> lines = newLines.get(ml.filePath());
            return lines != null && lines.containsKey(ml.lineStart()) ? ml.filePath() : null;
        }
        String match = null;
        for (Map.Entry<String, Map<Integer, String>> file : newLines.entrySet()) {
            if (file.getValue().containsKey(ml.lineStart())) {
                if (match != null) {
                    return null;
                }
                match = file.getKey();
            }
        }
        return match;
    }

    /** The finding's lines plus up to two lines of surrounding context. */
    private static String extractCodeSnippet(MlFinding ml, Map<Integer, String> fileLines) {
        int start = ml.lineStart();
        int end = ml.lineEnd() != null ? ml.lineEnd() : start;

        StringBuilder sb = new StringBuilder();
        for (int line = Math.max(1, start - 2); line <= end + 2; line++) {
            String text = fileLines.get(line);
            if (text != null) {
                sb.append(text).append("\n");
            }
        }
        String snippet = sb.toString().trim();
        return snippet.isEmpty() ? null : snippet;
    }

    // -----------------------------------------------------------------
    // GitHub comment markdown
    // -----------------------------------------------------------------

    String formatGithubComment(List<Finding> findings, BigDecimal score, String engine) {
        int critical = 0, major = 0, minor = 0;
        for (Finding f : findings) {
            String sev = f.getSeverity() == null ? "minor" : f.getSeverity().toLowerCase(Locale.ROOT);
            if ("critical".equals(sev)) critical++;
            else if ("major".equals(sev)) major++;
            else minor++;
        }
        return WebhookService.buildComment(findings.stream()
                        .map(f -> new MlFinding(
                                f.getFilePath(),
                                null,
                                f.getLineStart(),
                                f.getLineEnd(),
                                f.getAntiPattern(),
                                f.getCategory(),
                                f.getSeverity(),
                                f.getConfidence(),
                                f.getExplanation()))
                        .toList(),
                score, critical, major, minor, engine);
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private void updateRepositoryQualityScore(Repository repo, BigDecimal score) {
        if (repo == null) return;
        repo.setQualityScore(score);
        repositoryRepository.save(repo);
    }

    private void updateQualityMetric(Repository repo, BigDecimal score, List<Finding> findings) {
        if (repo == null) return;
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        QualityMetric metric = qualityMetricRepository
                .findByRepoAndDate(repo, today)
                .orElseGet(() -> QualityMetric.builder()
                        .repo(repo)
                        .date(today)
                        .avgQuality(BigDecimal.ZERO)
                        .prsReviewed(0)
                        .criticalCount(0)
                        .majorCount(0)
                        .minorCount(0)
                        .updatedAt(Instant.now())
                        .build());
        int prevCount = metric.getPrsReviewed();
        BigDecimal prevAvg = metric.getAvgQuality() == null ? BigDecimal.ZERO : metric.getAvgQuality();
        BigDecimal newAvg = prevCount == 0
                ? score
                : prevAvg.multiply(BigDecimal.valueOf(prevCount))
                        .add(score)
                        .divide(BigDecimal.valueOf(prevCount + 1L), 2, RoundingMode.HALF_UP);
        metric.setAvgQuality(newAvg);
        metric.setPrsReviewed(prevCount + 1);
        int critical = 0, major = 0, minor = 0;
        for (Finding f : findings) {
            String sev = f.getSeverity() == null ? "minor" : f.getSeverity().toLowerCase(Locale.ROOT);
            if ("critical".equals(sev)) critical++;
            else if ("major".equals(sev)) major++;
            else minor++;
        }
        metric.setCriticalCount(metric.getCriticalCount() + critical);
        metric.setMajorCount(metric.getMajorCount() + major);
        metric.setMinorCount(metric.getMinorCount() + minor);
        qualityMetricRepository.save(metric);
    }
}
