package com.codelens.service;

import com.codelens.dto.MlFinding;
import com.codelens.dto.MlReviewResponse;
import com.codelens.entity.Finding;
import com.codelens.entity.PullRequestEntity;
import com.codelens.entity.QualityMetric;
import com.codelens.entity.Repository;
import com.codelens.entity.User;
import com.codelens.repository.FindingRepository;
import com.codelens.repository.PullRequestRepository;
import com.codelens.repository.QualityMetricRepository;
import com.codelens.repository.RepositoryRepository;
import com.codelens.repository.UserRepository;
import com.codelens.security.EncryptionService;
import com.codelens.webhook.GitHubWebhookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Orchestrator: runs the full review flow off the request thread.
 *
 * <p>Invoked from {@link com.codelens.controller.WebhookController} as
 * {@code @Async("taskExecutor")}. The controller returns 200 to GitHub
 * immediately; this method runs in the background pool.</p>
 *
 * <p>All exceptions are caught: failures are persisted as
 * {@code status="failed"} + {@code error_message}, never re-thrown.
 * We don't want a worker hiccup to be visible to GitHub.</p>
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    static final String STATUS_PROCESSING = "processing";
    static final String STATUS_REVIEWED = "reviewed";
    static final String STATUS_FAILED = "failed";

    private final RepositoryRepository repositoryRepository;
    private final UserRepository userRepository;
    private final PullRequestRepository pullRequestRepository;
    private final FindingRepository findingRepository;
    private final QualityMetricRepository qualityMetricRepository;
    private final EncryptionService encryptionService;
    private final GitHubService githubService;
    private final MlWorkerService mlWorkerService;

    public WebhookService(RepositoryRepository repositoryRepository,
                          UserRepository userRepository,
                          PullRequestRepository pullRequestRepository,
                          FindingRepository findingRepository,
                          QualityMetricRepository qualityMetricRepository,
                          EncryptionService encryptionService,
                          GitHubService githubService,
                          MlWorkerService mlWorkerService) {
        this.repositoryRepository = repositoryRepository;
        this.userRepository = userRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.findingRepository = findingRepository;
        this.qualityMetricRepository = qualityMetricRepository;
        this.encryptionService = encryptionService;
        this.githubService = githubService;
        this.mlWorkerService = mlWorkerService;
    }

    @Async("taskExecutor")
    public void processAsync(GitHubWebhookEvent event, String repoGithubId) {
        Long githubId = parseLong(repoGithubId);
        if (githubId == null || event.pullRequest() == null) {
            log.warn("Webhook processAsync called with missing fields");
            return;
        }

        Repository repo;
        PullRequestEntity pr;
        try {
            repo = repositoryRepository.findByGithubId(githubId).orElse(null);
            if (repo == null) {
                log.info("Webhook for unknown repo {}", repoGithubId);
                return;
            }

            pr = upsertPullRequest(repo, event);

            // Idempotency: if we've already reviewed this PR's current head SHA,
            // don't redo the work.
            String incomingSha = event.pullRequest().headSha();
            if (STATUS_REVIEWED.equals(pr.getStatus())
                    && incomingSha != null
                    && incomingSha.equals(pr.getHeadSha())) {
                log.info("PR #{} already reviewed for head {}; skipping",
                        pr.getGithubPrNumber(), incomingSha);
                return;
            }

            User owner = repo.getOwner() == null
                    ? userRepository.findById(repo.getOwner() == null ? null : repo.getOwner().getId()).orElse(null)
                    : repo.getOwner();
            // The above is awkward; simplify: if owner is set, use it; else try DB.
            if (owner == null) {
                log.warn("Repository {} has no owner; cannot fetch GitHub token", repo.getFullName());
            }
            String decryptedToken = (owner == null || owner.getAccessToken() == null)
                    ? null
                    : encryptionService.decrypt(owner.getAccessToken());

            String diff = (decryptedToken == null)
                    ? ""
                    : githubService.getFileDiff(decryptedToken, repo.getFullName(), pr.getGithubPrNumber());
            String language = detectLanguage(diff);

            MlReviewResponse review = mlWorkerService.review(diff, language);

            // Replace previous findings for this PR — old findings would otherwise
            // stack up across re-reviews of the same PR.
            findingRepository.findAll().stream()
                    .filter(f -> f.getPullRequest() != null
                            && f.getPullRequest().getId() != null
                            && f.getPullRequest().getId().equals(pr.getId()))
                    .forEach(findingRepository::delete);

            BigDecimal quality = review == null || review.qualityScore() == null
                    ? BigDecimal.ZERO : review.qualityScore();
            int critical = 0, major = 0, minor = 0;
            if (review != null && review.findings() != null) {
                for (MlFinding ml : review.findings()) {
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
                    findingRepository.save(f);
                    if ("critical".equalsIgnoreCase(ml.severity())) critical++;
                    else if ("major".equalsIgnoreCase(ml.severity())) major++;
                    else if ("minor".equalsIgnoreCase(ml.severity())) minor++;
                }
            }

            pr.setQualityScore(quality);
            pr.setStatus(STATUS_REVIEWED);
            pr.setErrorMessage(null);
            pr.setReviewedAt(Instant.now());
            pullRequestRepository.save(pr);

            String markdown = buildComment(review == null ? null : review.findings(),
                    quality, critical, major, minor);

            if (decryptedToken != null) {
                try {
                    githubService.postPrComment(decryptedToken, repo.getFullName(),
                            pr.getGithubPrNumber(), markdown);
                } catch (Exception ex) {
                    log.warn("Failed to post GitHub PR comment for PR #{}: {}",
                            pr.getGithubPrNumber(), ex.getMessage());
                }
            }

            updateQualityMetric(repo, quality, critical, major, minor);

        } catch (Exception ex) {
            log.error("Webhook processing failed for repo {}: {}", repoGithubId, ex.getMessage(), ex);
            markFailed(githubId, event);
        }
    }

    private PullRequestEntity upsertPullRequest(Repository repo, GitHubWebhookEvent event) {
        int prNumber = event.pullRequest().number();
        Optional<PullRequestEntity> existing =
                pullRequestRepository.findByRepoAndGithubPrNumber(repo, prNumber);
        PullRequestEntity pr = existing.orElseGet(() -> PullRequestEntity.builder()
                .repo(repo)
                .githubPrNumber(prNumber)
                .status(STATUS_PROCESSING)
                .build());
        pr.setTitle(event.pullRequest().title());
        if (event.pullRequest().user() != null) {
            pr.setAuthorGithub(event.pullRequest().user().login());
        }
        pr.setHeadSha(event.pullRequest().headSha());
        pr.setGithubPrUrl(event.pullRequest().htmlUrl());
        pr.setStatus(STATUS_PROCESSING);
        return pullRequestRepository.save(pr);
    }

    private void markFailed(Long githubId, GitHubWebhookEvent event) {
        try {
            Repository repo = githubId == null ? null
                    : repositoryRepository.findByGithubId(githubId).orElse(null);
            if (repo == null || event.pullRequest() == null) return;
            Optional<PullRequestEntity> existing =
                    pullRequestRepository.findByRepoAndGithubPrNumber(repo, event.pullRequest().number());
            existing.ifPresent(pr -> {
                pr.setStatus(STATUS_FAILED);
                pr.setErrorMessage("processing failed; see server logs");
                pullRequestRepository.save(pr);
            });
        } catch (Exception inner) {
            log.error("Failed to mark PR as failed", inner);
        }
    }

    private void updateQualityMetric(Repository repo, BigDecimal quality,
                                     int critical, int major, int minor) {
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
                ? quality
                : prevAvg.multiply(BigDecimal.valueOf(prevCount))
                        .add(quality)
                        .divide(BigDecimal.valueOf(prevCount + 1L), 2, RoundingMode.HALF_UP);
        metric.setAvgQuality(newAvg);
        metric.setPrsReviewed(prevCount + 1);
        metric.setCriticalCount(metric.getCriticalCount() + critical);
        metric.setMajorCount(metric.getMajorCount() + major);
        metric.setMinorCount(metric.getMinorCount() + minor);
        qualityMetricRepository.save(metric);
    }

    static String buildComment(List<MlFinding> findings, BigDecimal quality,
                               int critical, int major, int minor) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 🔍 CodeLens Review\n\n");
        String emoji = quality == null ? "❓"
                : quality.compareTo(BigDecimal.valueOf(80)) >= 0 ? "✅"
                : quality.compareTo(BigDecimal.valueOf(60)) >= 0 ? "⚠️" : "❌";
        sb.append("**Quality Score: ")
                .append(quality == null ? "?" : quality.toPlainString())
                .append("/100** ")
                .append(emoji)
                .append("\n\n");

        if (findings == null || findings.isEmpty()) {
            sb.append("✅ No anti-patterns detected. Clean code!\n");
        } else {
            sb.append("### Findings (")
                    .append(critical).append(" critical · ")
                    .append(major).append(" major · ")
                    .append(minor).append(" minor)\n\n");
            sb.append("| Severity | Pattern | File | Lines | Confidence |\n");
            sb.append("|----------|---------|------|-------|------------|\n");
            for (MlFinding f : findings) {
                String sev = f.severity() == null ? "minor" : f.severity().toLowerCase(Locale.ROOT);
                String icon = switch (sev) {
                    case "critical" -> "🔴 Critical";
                    case "major" -> "🟠 Major";
                    default -> "🟡 Minor";
                };
                String lines = (f.lineStart() == null ? "?" : f.lineStart().toString())
                        + (f.lineEnd() == null || f.lineEnd().equals(f.lineStart())
                            ? ""
                            : "-" + f.lineEnd());
                String conf = f.confidence() == null
                        ? "?"
                        : f.confidence().multiply(BigDecimal.valueOf(100))
                                .setScale(0, RoundingMode.HALF_UP) + "%";
                sb.append("| ").append(icon)
                        .append(" | ").append(nullToDash(f.antiPattern()))
                        .append(" | `n/a`")
                        .append(" | ").append(lines)
                        .append(" | ").append(conf)
                        .append(" |\n");
            }
        }
        sb.append("\n---\n");
        sb.append("*Powered by [CodeLens](https://github.com/tanmay-alpha/codelens) — Semantic code review engine*\n");
        return sb.toString();
    }

    private static String nullToDash(String s) { return s == null ? "-" : s; }

    /**
     * Best-effort language detection from the diff. We look at the first
     * file path mentioned; the worker accepts "unknown" as a fallback.
     */
    static String detectLanguage(String diff) {
        if (diff == null) return "unknown";
        int idx = diff.indexOf("diff --git a/");
        if (idx < 0) return "unknown";
        int slash = diff.indexOf('/', idx + "diff --git a/".length());
        if (slash < 0) return "unknown";
        int dot = diff.indexOf('.', slash);
        if (dot < 0) return "unknown";
        int end = diff.indexOf(' ', dot);
        if (end < 0) end = diff.indexOf('\n', dot);
        if (end < 0) return "unknown";
        String ext = diff.substring(dot + 1, end).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "py" -> "python";
            case "js", "jsx", "ts", "tsx" -> "javascript";
            case "java" -> "java";
            default -> "unknown";
        };
    }

    private static Long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return null; }
    }
}