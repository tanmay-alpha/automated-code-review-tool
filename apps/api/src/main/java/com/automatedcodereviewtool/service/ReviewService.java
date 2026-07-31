package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.dto.MlFinding;
import com.automatedcodereviewtool.dto.MlReviewResponse;
import com.automatedcodereviewtool.dto.ReviewResult;
import com.automatedcodereviewtool.entity.CodeSample;
import com.automatedcodereviewtool.entity.Finding;
import com.automatedcodereviewtool.entity.IngestionOutbox;
import com.automatedcodereviewtool.entity.PredictionEvent;
import com.automatedcodereviewtool.entity.PullRequestEntity;
import com.automatedcodereviewtool.entity.QualityMetric;
import com.automatedcodereviewtool.entity.Repository;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern HUNK_HEADER_RE = Pattern.compile(
            "^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");
    private static final Pattern GIT_HEADER_RE = Pattern.compile(
            "^diff --git a/.+? b/(.+)$");
    private static final String OUTBOX_EVENT_TYPE = "DATASET_CAPTURE_REQUESTED";

    private final MlWorkerService mlWorkerService;
    private final FindingRepository findingRepository;
    private final PullRequestRepository pullRequestRepository;
    private final QualityMetricRepository qualityMetricRepository;
    private final RepositoryRepository repositoryRepository;
    private final GitHubService gitHubService;
    private final ReviewSampleBridge reviewSampleBridge;
    private final IngestionOutboxRepository outboxRepository;
    private final PredictionEventRepository predictionEventRepository;
    private final ObjectMapper objectMapper;

    public ReviewService(MlWorkerService mlWorkerService,
                         FindingRepository findingRepository,
                         PullRequestRepository pullRequestRepository,
                         QualityMetricRepository qualityMetricRepository,
                         RepositoryRepository repositoryRepository,
                         GitHubService gitHubService,
                         ReviewSampleBridge reviewSampleBridge,
                         IngestionOutboxRepository outboxRepository,
                         PredictionEventRepository predictionEventRepository,
                         ObjectMapper objectMapper) {
        this.mlWorkerService = mlWorkerService;
        this.findingRepository = findingRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.qualityMetricRepository = qualityMetricRepository;
        this.repositoryRepository = repositoryRepository;
        this.gitHubService = gitHubService;
        this.reviewSampleBridge = reviewSampleBridge;
        this.outboxRepository = outboxRepository;
        this.predictionEventRepository = predictionEventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Full review pipeline for a PR.
     *
     * <p>All detector output is recorded — persisted findings AND rejected
     * predictions. Unlocalized predictions become rejected events, not
     * silently dropped findings.</p>
     *
     * <p>Dataset capture is decoupled via the outbox: if outbox insertion
     * fails, the review still succeeds.</p>
     */
    @Transactional
    public ReviewResult orchestrateReview(PullRequestEntity pr, String diff) {
        if (diff == null || diff.isBlank()) {
            diff = "";
        }

        String language = MlWorkerService.detectLanguage(diff);
        log.info("Reviewing PR #{} — detected language: {}", pr.getGithubPrNumber(), language);

        MlReviewResponse mlResponse;
        try {
            mlResponse = mlWorkerService.review(diff, language);
        } catch (MlWorkerException ex) {
            log.error("ML worker failed for PR #{}: {}", pr.getGithubPrNumber(), ex.getMessage());
            pr.setStatus(WebhookService.STATUS_FAILED);
            pr.setErrorMessage(ex.getMessage());
            pullRequestRepository.save(pr);
            return ReviewResult.empty(ex.getMessage());
        }

        // Parse the diff to extract file paths and code snippets for findings.
        DiffParseResult parsed = parseDiff(diff);

        // Build a set of known files for unmapped-file detection.
        Set<String> knownFiles = new HashSet<>(parsed.fileOrder());

        // -- persist findings (batch delete + save) ------------------------
        findingRepository.deleteAllByPullRequestId(pr.getId());

        List<Finding> saved = new ArrayList<>();
        List<PredictionEvent> rejectedEvents = new ArrayList<>();
        BigDecimal score = MlWorkerService.computeQualityScore(mlResponse);

        if (mlResponse.findings() != null) {
            for (MlFinding ml : mlResponse.findings()) {
                // Record every prediction as an event (preserved for ML debugging).
                PredictionEvent event = buildPredictionEvent(pr, ml, mlResponse);
                String rejection = classifyRejection(ml, knownFiles, parsed);
                if (rejection != null) {
                    event.setStatus(rejection);
                    event.setRejectionReason(rejection.replace("rejected_", ""));
                    rejectedEvents.add(event);
                    log.warn("Rejected prediction: antiPattern={} reason={}", ml.antiPattern(), rejection);
                } else {
                    String resolvedPath = resolveFilePath(ml, parsed);
                    if (resolvedPath == null) {
                        event.setStatus("rejected_unmapped_file");
                        event.setRejectionReason("unmapped_file");
                        rejectedEvents.add(event);
                        log.warn("Dropping ML finding {} — line {} does not match any file in the diff",
                                ml.antiPattern(), ml.lineStart());
                        continue;
                    }
                    event.setStatus("persisted");
                    BigDecimal rawConfidence = ml.confidence();
                    BigDecimal clampedConfidence = (rawConfidence == null ? BigDecimal.ZERO
                            : rawConfidence.max(BigDecimal.ZERO).min(BigDecimal.ONE));
                    Finding f = Finding.builder()
                            .pullRequest(pr)
                            .filePath(resolvedPath)
                            .lineStart(ml.lineStart())
                            .lineEnd(ml.lineEnd())
                            .antiPattern(ml.antiPattern() == null ? "UNKNOWN" : ml.antiPattern())
                            .category(ml.category() == null ? "unknown" : ml.category())
                            .severity(ml.severity() == null ? "minor" : ml.severity())
                            .confidence(clampedConfidence)
                            .explanation(ml.explanation())
                            .codeSnippet(extractCodeSnippet(ml, parsed))
                            .engine(mlResponse.engine())
                            .modelVersion(mlResponse.modelVersion())
                            .taxonomyVersion(mlResponse.taxonomyVersion())
                            .build();
                    saved.add(findingRepository.save(f));
                }
                predictionEventRepository.save(event);
            }
        }

        // -- outbox: decouple dataset capture ------------------------------
        try {
            IngestionOutbox outbox = new IngestionOutbox();
            outbox.setEventType(OUTBOX_EVENT_TYPE);
            outbox.setAggregateType("pull_request");
            outbox.setAggregateId(pr.getRepo().getId());
            outbox.setPayload(buildOutboxPayload(pr, saved, rejectedEvents, diff));
            outbox.setStatus("pending");
            outbox.setAttemptCount(0);
            outbox.setAvailableAt(OffsetDateTime.now());
            outbox.setCreatedAt(OffsetDateTime.now());
            outboxRepository.save(outbox);
            log.info("Inserted outbox event {} for PR #{}", outbox.getId(), pr.getGithubPrNumber());
        } catch (Exception ex) {
            // Outbox failure must not affect the review result.
            log.error("Failed to insert outbox event for PR #{}: {}", pr.getGithubPrNumber(), ex.getMessage());
        }

        // -- update PR ----------------------------------------------------
        pr.setQualityScore(score);
        pr.setStatus(WebhookService.STATUS_REVIEWED);
        pr.setErrorMessage(null);
        pr.setReviewedAt(Instant.now());
        pullRequestRepository.save(pr);

        // -- update repository rolling quality score -----------------------
        updateRepositoryQualityScore(pr.getRepo(), score);

        // -- quality metric ------------------------------------------------
        updateQualityMetric(pr.getRepo(), score, saved);

        log.info("PR #{} reviewed — score={}, findings={}, rejected={}",
                pr.getGithubPrNumber(), score, saved.size(), rejectedEvents.size());
        return ReviewResult.of(pr, saved, score);
    }

    // -----------------------------------------------------------------
    // Rejected prediction classification
    // -----------------------------------------------------------------

    private String classifyRejection(MlFinding ml, Set<String> knownFiles, DiffParseResult parsed) {
        if (ml == null) return "rejected_invalid_line";

        String antiPattern = ml.antiPattern();
        if (antiPattern == null || antiPattern.isBlank() || "UNKNOWN".equals(antiPattern.toUpperCase())) {
            return "rejected_unknown_taxonomy";
        }

        BigDecimal conf = ml.confidence();
        if (conf != null && (conf.compareTo(BigDecimal.ZERO) < 0 || conf.compareTo(BigDecimal.ONE) > 0)) {
            return "rejected_invalid_confidence";
        }

        if (ml.lineStart() != null && !knownFiles.isEmpty()) {
            String resolved = resolveFilePath(ml, parsed);
            if (resolved == null) {
                return "rejected_unmapped_file";
            }
        }

        return null; // No rejection — this prediction is valid.
    }

    // -----------------------------------------------------------------
    // Prediction event builder
    // -----------------------------------------------------------------

    private PredictionEvent buildPredictionEvent(PullRequestEntity pr, MlFinding ml, MlReviewResponse response) {
        PredictionEvent event = new PredictionEvent();
        event.setPullRequestId(pr.getId());
        event.setFilePath(ml.filePath());
        event.setReportedLineStart(ml.lineStart());
        event.setReportedLineEnd(ml.lineEnd());
        event.setAntiPatternId(ml.antiPattern() == null ? "UNKNOWN" : ml.antiPattern());
        event.setCategory(ml.category() == null ? "unknown" : ml.category());
        event.setSeverity(ml.severity() == null ? "minor" : ml.severity());
        BigDecimal conf = ml.confidence() == null ? BigDecimal.ZERO
                : ml.confidence().max(BigDecimal.ZERO).min(BigDecimal.ONE);
        event.setConfidence(conf);
        event.setEngine(response.engine());
        event.setModelVersion(response.modelVersion());
        event.setTaxonomyVersion(response.taxonomyVersion());
        event.setStatus("persisted"); // Default; may be overwritten.
        event.setRawMetadata(buildSafeMetadata(ml, response));
        event.setCreatedAt(OffsetDateTime.now());
        return event;
    }

    /**
     * Build metadata JSON with no raw source content — only structural
     * information for ML debugging (e.g., window indices, scanner flags).
     */
    private String buildSafeMetadata(MlFinding ml, MlReviewResponse response) {
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
                                      List<PredictionEvent> rejectedEvents,
                                      String diff) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pullRequestId", pr.getId().toString());
        payload.put("githubPrNumber", pr.getGithubPrNumber());
        payload.put("headSha", pr.getHeadSha());
        payload.put("diff", diff == null ? "" : diff);
        payload.put("findingIds", findings.stream().map(f -> f.getId().toString()).toList());
        payload.put("rejectedEventIds", rejectedEvents.stream().map(e -> e.getId().toString()).toList());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    // -----------------------------------------------------------------
    // Diff parsing — maps line numbers to file paths and extracts snippets
    // -----------------------------------------------------------------

    record DiffParseResult(
            Map<Integer, String> lineToFile,
            Map<Integer, String> lineToText,
            List<String> fileOrder
    ) {}

    private DiffParseResult parseDiff(String diff) {
        Map<Integer, String> lineToFile = new HashMap<>();
        Map<Integer, String> lineToText = new HashMap<>();
        List<String> fileOrder = new ArrayList<>();
        Set<String> seenFiles = new LinkedHashSet<>();

        String currentFile = "unknown";
        int currentLine = 0;

        for (String rawLine : diff.split("\n")) {
            String line = rawLine;

            Matcher gitMatcher = GIT_HEADER_RE.matcher(line);
            if (gitMatcher.find()) {
                currentFile = gitMatcher.group(1);
                seenFiles.add(currentFile);
                continue;
            }

            Matcher hunkMatcher = HUNK_HEADER_RE.matcher(line);
            if (hunkMatcher.find()) {
                currentLine = Integer.parseInt(hunkMatcher.group(2));
                continue;
            }

            if (line.startsWith("+") && !line.startsWith("+++")) {
                lineToFile.put(currentLine, currentFile);
                lineToText.put(currentLine, line.substring(1));
                currentLine++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                // Removed lines don't advance the new file line number
            } else if (!line.startsWith("@@") && !line.startsWith("diff ")) {
                currentLine++;
            }
        }

        fileOrder.addAll(seenFiles);
        return new DiffParseResult(lineToFile, lineToText, fileOrder);
    }

    private String resolveFilePath(MlFinding ml, DiffParseResult parsed) {
        if (ml.lineStart() == null) {
            return null;
        }
        return parsed.lineToFile().get(ml.lineStart());
    }

    private String extractCodeSnippet(MlFinding ml, DiffParseResult parsed) {
        if (ml.lineStart() == null) {
            return null;
        }
        int start = ml.lineStart();
        int end = ml.lineEnd() != null ? ml.lineEnd() : start;

        StringBuilder sb = new StringBuilder();
        for (int line = Math.max(1, start - 2); line <= end + 2; line++) {
            String text = parsed.lineToText().get(line);
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

    String formatGithubComment(List<Finding> findings, BigDecimal score) {
        int critical = 0, major = 0, minor = 0;
        for (Finding f : findings) {
            String sev = f.getSeverity() == null ? "minor" : f.getSeverity().toLowerCase(Locale.ROOT);
            if ("critical".equals(sev)) critical++;
            else if ("major".equals(sev)) major++;
            else minor++;
        }
        return WebhookService.buildComment(findings.stream()
                        .map(f -> new com.automatedcodereviewtool.dto.MlFinding(
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
                score, critical, major, minor);
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
