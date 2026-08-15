package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.dto.MlFinding;
import com.automatedcodereviewtool.dto.ReviewResult;
import com.automatedcodereviewtool.entity.ProcessedWebhook;
import com.automatedcodereviewtool.entity.PullRequestEntity;
import com.automatedcodereviewtool.entity.Repository;
import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.repository.ProcessedWebhookRepository;
import com.automatedcodereviewtool.repository.PullRequestRepository;
import com.automatedcodereviewtool.repository.RepositoryRepository;
import com.automatedcodereviewtool.security.EncryptionService;
import com.automatedcodereviewtool.webhook.GitHubWebhookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/** Durable webhook ingress and restart-safe review processor. */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private static final Duration PROCESSING_LEASE = Duration.ofMinutes(10);

    static final String STATUS_PROCESSING = "processing";
    static final String STATUS_REVIEWED = "reviewed";
    static final String STATUS_FAILED = "failed";

    private final RepositoryRepository repositoryRepository;
    private final PullRequestRepository pullRequestRepository;
    private final ProcessedWebhookRepository processedWebhookRepository;
    private final EncryptionService encryptionService;
    private final GitHubService githubService;
    private final ReviewService reviewService;
    private final SecretRedactor secretRedactor;

    public WebhookService(RepositoryRepository repositoryRepository,
                          PullRequestRepository pullRequestRepository,
                          ProcessedWebhookRepository processedWebhookRepository,
                          EncryptionService encryptionService,
                          GitHubService githubService,
                          ReviewService reviewService,
                          SecretRedactor secretRedactor) {
        this.repositoryRepository = repositoryRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.processedWebhookRepository = processedWebhookRepository;
        this.encryptionService = encryptionService;
        this.githubService = githubService;
        this.reviewService = reviewService;
        this.secretRedactor = secretRedactor;
    }

    /**
     * Persist delivery identity and the PR state in one short transaction.
     * A false result means either the exact delivery or logical repo/PR/head
     * review already exists.
     */
    @Transactional
    public boolean receiveDelivery(String deliveryId, GitHubWebhookEvent event, long repoGithubId) {
        if (event.pullRequest() == null || event.pullRequest().headSha() == null
                || event.pullRequest().headSha().isBlank()) {
            throw new IllegalArgumentException("pull request head SHA is required");
        }

        Repository repo = repositoryRepository.findByGithubId(repoGithubId)
                .orElseThrow(() -> new IllegalArgumentException("unknown repository"));
        int inserted = processedWebhookRepository.insertReceived(
                deliveryId,
                repo.getId(),
                event.pullRequest().number(),
                event.pullRequest().headSha(),
                event.action());
        if (inserted == 0) {
            return false;
        }

        PullRequestEntity pr = pullRequestRepository
                .findByRepoAndGithubPrNumber(repo, event.pullRequest().number())
                .orElseGet(() -> PullRequestEntity.builder()
                        .repo(repo)
                        .githubPrNumber(event.pullRequest().number())
                        .status(STATUS_PROCESSING)
                        .build());
        pr.setTitle(event.pullRequest().title());
        if (event.pullRequest().user() != null) {
            pr.setAuthorGithub(event.pullRequest().user().login());
        }
        pr.setHeadSha(event.pullRequest().headSha());
        pr.setGithubPrUrl(event.pullRequest().htmlUrl());
        pr.setStatus(STATUS_PROCESSING);
        pr.setErrorMessage(null);
        pullRequestRepository.save(pr);
        return true;
    }

    @Async("taskExecutor")
    public void processAsync(String deliveryId) {
        processDelivery(deliveryId);
    }

    /** Recovers work after process restarts or abandoned async tasks. */
    @Scheduled(fixedDelayString = "${app.webhook.recovery-delay-ms:15000}")
    public void processDurableBacklog() {
        processedWebhookRepository.recoverStale(Instant.now().minus(PROCESSING_LEASE));
        processedWebhookRepository.deadLetterExhausted();
        for (String deliveryId : processedWebhookRepository.findEligibleDeliveryIds(10)) {
            processDelivery(deliveryId);
        }
    }

    void processDelivery(String deliveryId) {
        if (processedWebhookRepository.claim(deliveryId) != 1) {
            return;
        }

        try {
            ProcessedWebhook delivery = processedWebhookRepository.findById(deliveryId)
                    .orElseThrow(() -> new IllegalStateException("claimed delivery disappeared"));
            Repository repo = repositoryRepository.findWithOwnerById(delivery.getRepoId())
                    .orElseThrow(() -> new IllegalStateException("repository no longer exists"));
            PullRequestEntity pr = pullRequestRepository
                    .findByRepoAndGithubPrNumber(repo, delivery.getGithubPrNumber())
                    .orElseThrow(() -> new IllegalStateException("pull request state is missing"));

            if (!delivery.getHeadSha().equals(pr.getHeadSha())) {
                processedWebhookRepository.markTerminal(
                        deliveryId, "superseded", "A newer PR head is already scheduled");
                return;
            }

            User owner = repo.getOwner();
            if (owner == null || owner.getAccessToken() == null) {
                processedWebhookRepository.markTerminal(
                        deliveryId, "dead_letter", "Repository owner token is unavailable");
                return;
            }

            String token = encryptionService.decrypt(owner.getAccessToken());
            String diff = githubService.getFileDiff(token, repo.getFullName(), pr.getGithubPrNumber());
            ReviewResult result = reviewService.orchestrateReview(pr, diff);
            if (!result.success()) {
                failDelivery(delivery, result.errorMessage());
                return;
            }

            String warning = null;
            try {
                String markdown = reviewService.formatGithubComment(
                        result.findings(), result.qualityScore());
                githubService.postPrComment(
                        token, repo.getFullName(), pr.getGithubPrNumber(), markdown);
            } catch (Exception ex) {
                // The review and ingestion event are already durable. Retrying the
                // whole review could double-post when GitHub accepted a request but
                // its response was lost, so retain a visible warning instead.
                warning = "Review persisted but GitHub comment failed: " + safeError(ex);
                log.warn("{}", warning);
            }
            processedWebhookRepository.markTerminal(deliveryId, "completed", warning);
        } catch (Exception ex) {
            ProcessedWebhook delivery = processedWebhookRepository.findById(deliveryId).orElse(null);
            if (delivery != null) {
                failDelivery(delivery, safeError(ex));
            }
            log.error("Webhook delivery {} failed: {}", deliveryId, safeError(ex));
        }
    }

    private void failDelivery(ProcessedWebhook delivery, String error) {
        int attempt = Math.max(1, delivery.getAttemptCount());
        long baseSeconds = Math.min(600L, 10L << Math.min(attempt, 5));
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, baseSeconds / 4L));
        processedWebhookRepository.markFailed(
                delivery.getDeliveryId(),
                Instant.now().plusSeconds(baseSeconds + jitter),
                secretRedactor.redact(error == null ? "review processing failed" : error));
    }

    private String safeError(Exception ex) {
        String error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        error = secretRedactor.redact(error);
        return error.length() <= 2000 ? error : error.substring(0, 2000);
    }

    static String buildComment(List<MlFinding> findings, BigDecimal quality,
                               int critical, int major, int minor) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 🔍 automated-code-review-tool Review\n\n");
        String emoji = quality == null ? "❓"
                : quality.compareTo(BigDecimal.valueOf(80)) >= 0 ? "✅"
                : quality.compareTo(BigDecimal.valueOf(60)) >= 0 ? "⚠️" : "❌";
        sb.append("**Quality Score: ")
                .append(quality == null ? "?" : quality.toPlainString())
                .append("/100** ").append(emoji).append("\n\n");

        if (findings == null || findings.isEmpty()) {
            sb.append("✅ No anti-patterns detected. Clean code!\n");
        } else {
            sb.append("### Findings (")
                    .append(critical).append(" critical · ")
                    .append(major).append(" major · ")
                    .append(minor).append(" minor)\n\n");
            sb.append("| Severity | Pattern | File | Lines | Confidence |\n");
            sb.append("|----------|---------|------|-------|------------|\n");
            for (MlFinding finding : findings) {
                String severity = finding.severity() == null
                        ? "minor" : finding.severity().toLowerCase(Locale.ROOT);
                String icon = switch (severity) {
                    case "critical" -> "🔴 Critical";
                    case "major" -> "🟠 Major";
                    default -> "🟡 Minor";
                };
                String lines = (finding.lineStart() == null ? "?" : finding.lineStart().toString())
                        + (finding.lineEnd() == null || finding.lineEnd().equals(finding.lineStart())
                        ? "" : "-" + finding.lineEnd());
                String confidence = finding.confidence() == null ? "?"
                        : finding.confidence().multiply(BigDecimal.valueOf(100))
                        .setScale(0, RoundingMode.HALF_UP) + "%";
                sb.append("| ").append(icon)
                        .append(" | ").append(nullToDash(finding.antiPattern()))
                        .append(" | `").append(nullToDash(finding.filePath())).append('`')
                        .append(" | ").append(lines)
                        .append(" | ").append(confidence).append(" |\n");
            }
        }
        sb.append("\n---\n");
        sb.append("*Powered by [automated-code-review-tool](https://github.com/tanmay-alpha/automated-code-review-tool) — Semantic code review engine*\n");
        return sb.toString();
    }

    private static String nullToDash(String value) {
        return value == null ? "-" : value;
    }
}
