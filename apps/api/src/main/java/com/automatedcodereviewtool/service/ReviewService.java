package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.dto.MlFinding;
import com.automatedcodereviewtool.dto.MlReviewResponse;
import com.automatedcodereviewtool.dto.ReviewResult;
import com.automatedcodereviewtool.entity.CodeSample;
import com.automatedcodereviewtool.entity.Finding;
import com.automatedcodereviewtool.entity.PullRequestEntity;
import com.automatedcodereviewtool.entity.QualityMetric;
import com.automatedcodereviewtool.entity.Repository;
import com.automatedcodereviewtool.exception.MlWorkerException;
import com.automatedcodereviewtool.repository.FindingRepository;
import com.automatedcodereviewtool.repository.PullRequestRepository;
import com.automatedcodereviewtool.repository.QualityMetricRepository;
import com.automatedcodereviewtool.repository.RepositoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orchestrates a single review: call ML worker → persist findings →
 * update PR state → build GitHub comment markdown.
 *
 * <p>Wrapped in {@code @Transactional} so the PR update and all
 * {@link Finding} inserts succeed or roll back together.</p>
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    // Regex to parse unified diff headers: @@ -oldStart,oldCount +newStart,newCount @@
    private static final Pattern HUNK_HEADER_RE = Pattern.compile(
            "^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");
    // Regex to parse diff --git lines: diff --git a/<path> b/<path>
    private static final Pattern GIT_HEADER_RE = Pattern.compile(
            "^diff --git a/.+? b/(.+)$");

    private final MlWorkerService mlWorkerService;
    private final FindingRepository findingRepository;
    private final PullRequestRepository pullRequestRepository;
    private final QualityMetricRepository qualityMetricRepository;
    private final RepositoryRepository repositoryRepository;
    private final GitHubService gitHubService;
    private final ReviewSampleBridge reviewSampleBridge;

    public ReviewService(MlWorkerService mlWorkerService,
                         FindingRepository findingRepository,
                         PullRequestRepository pullRequestRepository,
                         QualityMetricRepository qualityMetricRepository,
                         RepositoryRepository repositoryRepository,
                         GitHubService gitHubService,
                         ReviewSampleBridge reviewSampleBridge) {
        this.mlWorkerService = mlWorkerService;
        this.findingRepository = findingRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.qualityMetricRepository = qualityMetricRepository;
        this.repositoryRepository = repositoryRepository;
        this.gitHubService = gitHubService;
        this.reviewSampleBridge = reviewSampleBridge;
    }

    /**
     * Full review pipeline for a PR.
     *
     * <p>On any {@link MlWorkerException} the PR is marked as
     * {@code failed} and an empty result is returned — the caller
     * (WebhookService) handles skipping the GitHub comment.</p>
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

        // -- persist findings (batch delete + save) ------------------------
        findingRepository.deleteAllByPullRequestId(pr.getId());

        // Persist hunk-level samples before the ML response is consumed,
        // then stamp findings with the corresponding code sample ids.
        List<CodeSample> samples = reviewSampleBridge.persistHunksForReview(
                pr.getRepo().getId(), pr.getId(), pr.getHeadSha(), diff);

        List<Finding> saved = new ArrayList<>();
        BigDecimal score = MlWorkerService.computeQualityScore(mlResponse);

        if (mlResponse.findings() != null) {
            for (MlFinding ml : mlResponse.findings()) {
                String resolvedPath = resolveFilePath(ml, parsed);
                if (resolvedPath == null) {
                    log.warn("Dropping ML finding {} — line {} does not match any file in the diff",
                            ml.antiPattern(), ml.lineStart());
                    continue;
                }
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
        }

        // Attach the persisted code sample ids on every matched finding so
        // downstream analysts can re-link a finding to the exact diff hunk.
        reviewSampleBridge.stampFindings(saved, samples, mlResponse);

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

        log.info("PR #{} reviewed — score={}, findings={}",
                pr.getGithubPrNumber(), score, saved.size());
        return ReviewResult.of(pr, saved, score);
    }

    // -----------------------------------------------------------------
    // Diff parsing — maps line numbers to file paths and extracts snippets
    // -----------------------------------------------------------------

    /**
     * Result of parsing a unified diff. Contains a map from line number
     * to file path, and a map from line number to the line text.
     */
    record DiffParseResult(
            Map<Integer, String> lineToFile,
            Map<Integer, String> lineToText,
            List<String> fileOrder
    ) {}

    /**
     * Parses a unified diff into per-line file-path and text maps.
     * This enables populating {@link Finding#filePath} and
     * {@link Finding#codeSnippet} from the diff data.
     */
    private DiffParseResult parseDiff(String diff) {
        Map<Integer, String> lineToFile = new HashMap<>();
        Map<Integer, String> lineToText = new HashMap<>();
        List<String> fileOrder = new ArrayList<>();
        Set<String> seenFiles = new LinkedHashSet<>();

        String currentFile = "unknown";
        int currentLine = 0;

        for (String rawLine : diff.split("\n")) {
            String line = rawLine;

            // Track file changes from diff --git headers
            Matcher gitMatcher = GIT_HEADER_RE.matcher(line);
            if (gitMatcher.find()) {
                currentFile = gitMatcher.group(1);
                seenFiles.add(currentFile);
                continue;
            }

            // Track hunk headers to know the new file's starting line
            Matcher hunkMatcher = HUNK_HEADER_RE.matcher(line);
            if (hunkMatcher.find()) {
                // The +-side starting line number
                currentLine = Integer.parseInt(hunkMatcher.group(2));
                continue;
            }

            // Track content lines (the + lines in the new file)
            if (line.startsWith("+") && !line.startsWith("+++")) {
                lineToFile.put(currentLine, currentFile);
                lineToText.put(currentLine, line.substring(1));
                currentLine++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                // Removed lines don't advance the new file line number
                // but we still track them for reference
            } else if (!line.startsWith("@@") && !line.startsWith("diff ")) {
                // Context lines in the new file
                currentLine++;
            }
        }

        fileOrder.addAll(seenFiles);
        return new DiffParseResult(lineToFile, lineToText, fileOrder);
    }

    /**
     * Resolve the file path for a finding based on its line number.
     *
     * <p>Returns {@code null} when the line number does not map to any
     * file in the diff and no diff files exist at all. Callers must
     * drop these findings instead of guessing the first file — picking
     * the first file would mis-attribute findings to unrelated paths.</p>
     */
    private String resolveFilePath(MlFinding ml, DiffParseResult parsed) {
        if (ml.lineStart() == null) {
            return null;
        }
        return parsed.lineToFile().get(ml.lineStart());
    }

    /**
     * Extract a code snippet around the flagged lines from the parsed diff.
     * Returns up to 5 lines of context (2 before, the flagged line, 2 after).
     */
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
        // updatedAt is automatically set by @UpdateTimestamp
        qualityMetricRepository.save(metric);
    }
}
