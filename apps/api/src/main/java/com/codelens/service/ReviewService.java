package com.codelens.service;

import com.codelens.dto.MlFinding;
import com.codelens.dto.MlReviewResponse;
import com.codelens.dto.ReviewResult;
import com.codelens.entity.Finding;
import com.codelens.entity.PullRequestEntity;
import com.codelens.entity.QualityMetric;
import com.codelens.entity.Repository;
import com.codelens.exception.MlWorkerException;
import com.codelens.repository.FindingRepository;
import com.codelens.repository.PullRequestRepository;
import com.codelens.repository.QualityMetricRepository;
import com.codelens.repository.RepositoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    private final MlWorkerService mlWorkerService;
    private final FindingRepository findingRepository;
    private final PullRequestRepository pullRequestRepository;
    private final QualityMetricRepository qualityMetricRepository;
    private final RepositoryRepository repositoryRepository;
    private final GitHubService gitHubService;

    public ReviewService(MlWorkerService mlWorkerService,
                         FindingRepository findingRepository,
                         PullRequestRepository pullRequestRepository,
                         QualityMetricRepository qualityMetricRepository,
                         RepositoryRepository repositoryRepository,
                         GitHubService gitHubService) {
        this.mlWorkerService = mlWorkerService;
        this.findingRepository = findingRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.qualityMetricRepository = qualityMetricRepository;
        this.repositoryRepository = repositoryRepository;
        this.gitHubService = gitHubService;
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

        String language = detectLanguage(diff);
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

        // -- persist findings (batch delete + save) ------------------------
        findingRepository.deleteAllByPullRequestId(pr.getId());
        List<Finding> saved = new ArrayList<>();
        BigDecimal score = computeQualityScore(mlResponse);

        if (mlResponse.findings() != null) {
            for (MlFinding ml : mlResponse.findings()) {
                Finding f = Finding.builder()
                        .pullRequest(pr)
                        .filePath("n/a")
                        .lineStart(ml.lineStart())
                        .lineEnd(ml.lineEnd())
                        .antiPattern(ml.antiPattern() == null ? "UNKNOWN" : ml.antiPattern())
                        .category(ml.category() == null ? "unknown" : ml.category())
                        .severity(ml.severity() == null ? "minor" : ml.severity())
                        .confidence(ml.confidence() == null ? BigDecimal.ZERO : ml.confidence())
                        .explanation(ml.explanation())
                        .build();
                saved.add(findingRepository.save(f));
            }
        }

        // -- update PR ----------------------------------------------------
        pr.setQualityScore(score);
        pr.setStatus(WebhookService.STATUS_REVIEWED);
        pr.setErrorMessage(null);
        pr.setReviewedAt(Instant.now());
        pullRequestRepository.save(pr);

        // -- quality metric ------------------------------------------------
        updateQualityMetric(pr.getRepo(), score, saved);

        log.info("PR #{} reviewed — score={}, findings={}",
                pr.getGithubPrNumber(), score, saved.size());
        return ReviewResult.of(pr, saved, score);
    }

    // -----------------------------------------------------------------
    // Language detection — counts lines touched per extension, picks the winner
    // -----------------------------------------------------------------

    /**
     * Inspect the diff and pick the most-represented language by
     * counting the number of lines in files of each extension.
     */
    String detectLanguage(String diff) {
        if (diff == null || diff.isBlank()) return "python";
        int py = 0, js = 0, java = 0;
        String currentFile = null;
        for (String raw : diff.split("\n")) {
            String line = raw.stripLeading();
            if (line.startsWith("diff --git")) {
                // Extract file extension from the b/ path
                int b = line.indexOf(" b/");
                if (b > 0) {
                    currentFile = line.substring(b + 3);
                } else {
                    currentFile = null;
                }
            }
            if (currentFile != null && (line.startsWith("+") || line.startsWith("-"))
                    && !line.startsWith("+++") && !line.startsWith("---")) {
                // Count only added/removed (non-context) lines
                String lower = currentFile.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".py")) py++;
                else if (lower.endsWith(".js") || lower.endsWith(".jsx")
                        || lower.endsWith(".ts") || lower.endsWith(".tsx")) js++;
                else if (lower.endsWith(".java")) java++;
            }
        }
        // Bonus for .py default — if no signal at all, fall back
        if (py >= js && py >= java && py > 0) return "python";
        if (js >= py && js >= java && js > 0) return "javascript";
        if (java > 0) return "java";
        return "python";
    }

    /**
     * Build the GitHub PR comment markdown. Delegates to the static
     * helper in {@link WebhookService}.
     */
    String formatGithubComment(List<Finding> findings, BigDecimal score) {
        // Count severities
        int critical = 0, major = 0, minor = 0;
        for (Finding f : findings) {
            String sev = f.getSeverity() == null ? "minor" : f.getSeverity().toLowerCase(Locale.ROOT);
            if ("critical".equals(sev)) critical++;
            else if ("major".equals(sev)) major++;
            else if ("minor".equals(sev)) minor++;
        }
        return WebhookService.buildComment(findings.stream()
                        .map(f -> new com.codelens.dto.MlFinding(
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
    // Quality score — penalty model
    // -----------------------------------------------------------------

    /**
     * Compute a 0–100 quality score. Uses the worker's {@code qualityScore}
     * when available; falls back to a penalty calculation from findings.
     */
    static BigDecimal computeQualityScore(MlReviewResponse response) {
        if (response == null) return BigDecimal.ZERO;
        BigDecimal ws = response.qualityScore();
        if (ws != null) {
            return ws.min(BigDecimal.valueOf(100)).max(BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP);
        }
        // Fallback: 100 minus weighted penalties
        if (response.findings() == null || response.findings().isEmpty()) {
            return BigDecimal.valueOf(100);
        }
        BigDecimal penalty = BigDecimal.ZERO;
        for (MlFinding f : response.findings()) {
            BigDecimal conf = f.confidence() == null ? BigDecimal.ZERO : f.confidence();
            BigDecimal weight = switch (f.severity() == null ? "minor" : f.severity().toLowerCase(Locale.ROOT)) {
                case "critical" -> BigDecimal.valueOf(20);
                case "major" -> BigDecimal.valueOf(10);
                default -> BigDecimal.valueOf(5);
            };
            penalty = penalty.add(weight.multiply(conf));
        }
        BigDecimal score = BigDecimal.valueOf(100).subtract(penalty);
        return score.max(BigDecimal.ZERO).min(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

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