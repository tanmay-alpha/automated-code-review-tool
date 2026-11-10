package com.automatedcodereviewtool.service;

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

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Durable webhook ingress and restart-safe review processor.
 *
 * <p>Lifecycle of a delivery: {@code received} → {@code processing} (an
 * attempt-fenced lease) → {@code completed} | {@code superseded} |
 * {@code failed} (retry scheduled) | {@code dead_letter}. Publishing the
 * result to GitHub is a separate, independently retried step owned by
 * {@link ReviewPublisher}.</p>
 */
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
    private final ReviewPublisher reviewPublisher;
    private final RepoReviewPolicy reviewPolicy;
    private final SecretRedactor secretRedactor;

    public WebhookService(RepositoryRepository repositoryRepository,
                          PullRequestRepository pullRequestRepository,
                          ProcessedWebhookRepository processedWebhookRepository,
                          EncryptionService encryptionService,
                          GitHubService githubService,
                          ReviewService reviewService,
                          ReviewPublisher reviewPublisher,
                          RepoReviewPolicy reviewPolicy,
                          SecretRedactor secretRedactor) {
        this.repositoryRepository = repositoryRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.processedWebhookRepository = processedWebhookRepository;
        this.encryptionService = encryptionService;
        this.githubService = githubService;
        this.reviewService = reviewService;
        this.reviewPublisher = reviewPublisher;
        this.reviewPolicy = reviewPolicy;
        this.secretRedactor = secretRedactor;
    }

    /**
     * Persist delivery identity and the PR state in one short transaction.
     * A false result means the repo is disconnected, or the exact delivery
     * or logical repo/PR/head review already exists.
     *
     * <p>The PR row is locked before it is updated, so a review transaction
     * of an older head either commits first (and is then superseded by this
     * head) or sees this head and discards its own result.</p>
     */
    @Transactional
    public boolean receiveDelivery(String deliveryId, GitHubWebhookEvent event, long repoGithubId) {
        if (event.pullRequest() == null || event.pullRequest().headSha() == null
                || event.pullRequest().headSha().isBlank()) {
            throw new IllegalArgumentException("pull request head SHA is required");
        }

        Repository repo = repositoryRepository.findByGithubId(repoGithubId)
                .orElseThrow(() -> new IllegalArgumentException("unknown repository"));
        if (!repo.isActive()) {
            return false;
        }
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
                .findForUpdateByRepoAndGithubPrNumber(repo, event.pullRequest().number())
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
        Instant staleBefore = Instant.now().minus(PROCESSING_LEASE);
        processedWebhookRepository.recoverStale(staleBefore);
        processedWebhookRepository.deadLetterExhausted(staleBefore);
        for (String deliveryId : processedWebhookRepository.findEligibleDeliveryIds(10)) {
            processDelivery(deliveryId);
        }
    }

    void processDelivery(String deliveryId) {
        ProcessedWebhook delivery = processedWebhookRepository.findById(deliveryId).orElse(null);
        if (delivery == null) {
            return;
        }
        int observedAttempt = delivery.getAttemptCount();
        if (processedWebhookRepository.claim(deliveryId, observedAttempt) != 1) {
            return;
        }
        // Fencing token: every completion write matches only this attempt, so a
        // worker whose lease expired can't overwrite a newer claim.
        int attempt = observedAttempt + 1;
        ReviewRun run = null;
        PullRequestEntity pr = null;

        try {
            Repository repo = repositoryRepository.findWithOwnerById(delivery.getRepoId())
                    .orElseThrow(() -> new IllegalStateException("repository no longer exists"));
            pr = pullRequestRepository
                    .findByRepoAndGithubPrNumber(repo, delivery.getGithubPrNumber())
                    .orElseThrow(() -> new IllegalStateException("pull request state is missing"));

            if (!delivery.getHeadSha().equals(pr.getHeadSha())) {
                finish(deliveryId, attempt, "superseded", "A newer PR head is already scheduled");
                return;
            }

            // Captured on the run (and the publication row), so a settings change
            // after this point does not alter this delivery's publication.
            boolean postComments = reviewPolicy.postComments(repo);
            run = new ReviewRun(deliveryId, attempt, delivery.getHeadSha(),
                    postComments, postComments && reviewPolicy.inlineComments(repo));

            User owner = repo.getOwner();
            if (owner == null || owner.getAccessToken() == null) {
                String error = GitHubApiException.Kind.AUTH_REVOKED.code()
                        + ": repository owner token is unavailable; reconnect the repository";
                reviewService.failPullRequest(pr.getId(), run, error);
                finish(deliveryId, attempt, "dead_letter", error);
                return;
            }

            String token = encryptionService.decrypt(owner.getAccessToken());
            String diff;
            try {
                diff = githubService.getFileDiff(token, repo.getFullName(), pr.getGithubPrNumber());
            } catch (GitHubApiException ex) {
                handleGitHubFailure(delivery, attempt, run, pr, ex);
                return;
            }

            ReviewResult result = reviewService.orchestrateReview(pr, diff, run);
            switch (result.outcome()) {
                case REVIEWED -> {
                    finish(deliveryId, attempt, "completed", null);
                    // The publication row committed with the findings; publishing now
                    // is an optimisation over waiting for the publication poller,
                    // which retries it if this attempt cannot run.
                    try {
                        reviewPublisher.publish(result.publicationId());
                    } catch (RuntimeException ex) {
                        log.warn("Publication {} deferred to the poller: {}", result.publicationId(), safeError(ex));
                    }
                }
                case SUPERSEDED -> finish(deliveryId, attempt, "superseded", result.errorMessage());
                case LEASE_LOST -> log.warn("Delivery {} attempt {} lost its lease; result discarded",
                        deliveryId, attempt);
                case FAILED -> {
                    if (result.retryable()) {
                        failDelivery(delivery, attempt, run, pr, result.errorMessage(), null);
                    } else {
                        finish(deliveryId, attempt, "dead_letter", secretRedactor.redact(result.errorMessage()));
                    }
                }
            }
        } catch (Exception ex) {
            log.error("Webhook delivery {} failed: {}", deliveryId, safeError(ex));
            failDelivery(delivery, attempt, run, pr, safeError(ex), null);
        }
    }

    /**
     * Revoked tokens, vanished PRs, missing permissions and diffs GitHub
     * refuses to render cannot succeed on retry: they dead-letter with the
     * classified code and mark the PR failed. Rate limits retry at the
     * reset time; 5xx and I/O failures retry with backoff.
     */
    private void handleGitHubFailure(ProcessedWebhook delivery, int attempt, ReviewRun run,
                                     PullRequestEntity pr, GitHubApiException ex) {
        String error = safe(ex.describe());
        if (ex.retryable()) {
            log.warn("Delivery {} attempt {}: retryable GitHub failure: {}", delivery.getDeliveryId(), attempt, error);
            failDelivery(delivery, attempt, run, pr, error, ex.retryAt());
            return;
        }
        log.warn("Delivery {} attempt {}: terminal GitHub failure: {}", delivery.getDeliveryId(), attempt, error);
        reviewService.failPullRequest(pr.getId(), run, error);
        finish(delivery.getDeliveryId(), attempt, "dead_letter", error);
    }

    private void finish(String deliveryId, int attempt, String status, String message) {
        if (processedWebhookRepository.markTerminal(deliveryId, attempt, status, message) != 1) {
            log.warn("Webhook delivery {} attempt {} lost its lease; '{}' result discarded",
                    deliveryId, attempt, status);
        }
    }

    /**
     * Schedule a retry (exponential backoff, or {@code notBefore} for a rate
     * limit). The final attempt also marks the PR failed, so a dead-lettered
     * delivery never leaves its PR showing {@code processing}.
     */
    private void failDelivery(ProcessedWebhook delivery, int attempt, ReviewRun run, PullRequestEntity pr,
                              String error, Instant notBefore) {
        String safeError = safe(error == null ? "review processing failed" : error);
        if (attempt >= delivery.getMaxAttempts() && run != null && pr != null) {
            reviewService.failPullRequest(pr.getId(), run, safeError);
        }
        long baseSeconds = Math.min(600L, 10L << Math.min(attempt, 5));
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, baseSeconds / 4L));
        Instant retryAt = notBefore != null ? notBefore : Instant.now().plusSeconds(baseSeconds + jitter);
        int updated = processedWebhookRepository.markFailed(delivery.getDeliveryId(), attempt, retryAt, safeError);
        if (updated != 1) {
            log.warn("Webhook delivery {} attempt {} lost its lease; failure not recorded",
                    delivery.getDeliveryId(), attempt);
        }
    }

    private String safeError(Exception ex) {
        return safe(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
    }

    private String safe(String error) {
        String redacted = secretRedactor.redact(error);
        return redacted.length() <= 2000 ? redacted : redacted.substring(0, 2000);
    }
}
