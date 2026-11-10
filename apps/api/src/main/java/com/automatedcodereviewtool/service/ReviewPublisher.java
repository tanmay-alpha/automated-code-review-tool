package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.entity.Finding;
import com.automatedcodereviewtool.entity.PullRequestEntity;
import com.automatedcodereviewtool.entity.Repository;
import com.automatedcodereviewtool.entity.ReviewPublication;
import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.repository.FindingRepository;
import com.automatedcodereviewtool.repository.PullRequestRepository;
import com.automatedcodereviewtool.repository.ReviewPublicationRepository;
import com.automatedcodereviewtool.security.EncryptionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Publishes persisted reviews to GitHub, independently of analysis.
 *
 * <p>Each publication is claimed under an attempt-fenced lease (one at a
 * time per PR) and retried with backoff. Every external effect has a stable
 * identity: the PR's single summary comment is found by a hidden marker
 * written by the repository owner's token and edited in place, and the
 * inline review of a head is found by a marker containing that head. A
 * retry after a lost acknowledgement therefore edits or reuses what GitHub
 * already accepted instead of posting a duplicate.</p>
 *
 * <p>Before writing, the publisher re-reads the PR head from the database
 * and from GitHub and marks the publication {@code superseded} if the PR
 * has moved on, so findings are never published against a stale head.</p>
 */
@Service
public class ReviewPublisher {

    private static final Logger log = LoggerFactory.getLogger(ReviewPublisher.class);

    /** Lease is refreshed and checked immediately before each external write. */
    static final Duration LEASE = Duration.ofMinutes(5);
    private static final int BATCH_SIZE = 10;
    private static final int MAX_ERROR_CHARS = 2000;

    private final ReviewPublicationRepository publicationRepository;
    private final PullRequestRepository pullRequestRepository;
    private final FindingRepository findingRepository;
    private final GitHubService gitHubService;
    private final EncryptionService encryptionService;
    private final SecretRedactor secretRedactor;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ReviewPublisher(ReviewPublicationRepository publicationRepository,
                           PullRequestRepository pullRequestRepository,
                           FindingRepository findingRepository,
                           GitHubService gitHubService,
                           EncryptionService encryptionService,
                           SecretRedactor secretRedactor,
                           ObjectMapper objectMapper,
                           PlatformTransactionManager transactionManager) {
        this.publicationRepository = publicationRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.findingRepository = findingRepository;
        this.gitHubService = gitHubService;
        this.encryptionService = encryptionService;
        this.secretRedactor = secretRedactor;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Retries due publications and recovers ones whose publisher died mid-lease. */
    @Scheduled(fixedDelayString = "${app.publication.poll-delay-ms:15000}")
    public void publishDue() {
        Instant staleBefore = Instant.now().minus(LEASE);
        publicationRepository.failExhausted(staleBefore);
        for (String id : publicationRepository.findDueIds(staleBefore, BATCH_SIZE)) {
            publish(UUID.fromString(id));
        }
    }

    /** Attempt one publication now. Never throws: failures are recorded on the row. */
    public void publish(UUID publicationId) {
        ReviewPublication publication = publicationRepository.findById(publicationId).orElse(null);
        if (publication == null || !ReviewPublication.PENDING.equals(publication.getStatus())) {
            return;
        }
        int observedAttempt = publication.getAttemptCount();
        if (!claim(publication, observedAttempt)) {
            return;
        }
        int attempt = observedAttempt + 1;
        try {
            publishClaimed(publication, attempt);
        } catch (SupersededPublication ex) {
            terminal(publication, attempt, ReviewPublication.SUPERSEDED, ex.getMessage());
        } catch (LostPublicationLease ex) {
            log.warn("Publication {} attempt {} lost its lease before writing", publicationId, attempt);
        } catch (GitHubApiException ex) {
            String error = safe(ex.describe());
            if (ex.retryable()) {
                retry(publication, attempt, ex.retryAt(), error);
            } else {
                terminal(publication, attempt, ReviewPublication.FAILED, error);
            }
        } catch (Exception ex) {
            String error = safe(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            log.error("Publication {} attempt {} failed: {}", publicationId, attempt, error);
            retry(publication, attempt, null, error);
        }
    }

    /** Locks the PR row so the one-lease-per-PR check in the claim cannot race. */
    private boolean claim(ReviewPublication publication, int observedAttempt) {
        Boolean claimed = transactionTemplate.execute(status -> {
            if (pullRequestRepository.findForUpdateById(publication.getPrId()).isEmpty()) {
                return false;
            }
            return publicationRepository.claim(publication.getId(), observedAttempt,
                    Instant.now().minus(LEASE)) == 1;
        });
        return Boolean.TRUE.equals(claimed);
    }

    private void publishClaimed(ReviewPublication publication, int attempt) {
        String headSha = publication.getHeadSha();
        PullRequestEntity pr = pullRequestRepository.findWithRepoAndOwnerById(publication.getPrId()).orElse(null);
        if (pr == null) {
            terminal(publication, attempt, ReviewPublication.FAILED, "pull request no longer exists");
            return;
        }
        Repository repo = pr.getRepo();
        if (!repo.isActive()) {
            terminal(publication, attempt, ReviewPublication.SKIPPED, "repository was disconnected");
            return;
        }
        User owner = repo.getOwner();
        if (owner == null || owner.getAccessToken() == null || owner.getGithubId() == null) {
            terminal(publication, attempt, ReviewPublication.FAILED,
                    GitHubApiException.Kind.AUTH_REVOKED.code() + ": repository owner token is unavailable");
            return;
        }

        // Findings first, then the head: if the head still matches after the
        // read, the findings were written for this head.
        List<Finding> findings = findingRepository.findAllByPullRequestId(pr.getId());
        String dbHead = pullRequestRepository.findHeadShaById(pr.getId()).orElse(null);
        if (!headSha.equals(dbHead)) {
            terminal(publication, attempt, ReviewPublication.SUPERSEDED,
                    "PR head moved to " + dbHead + " before publication");
            return;
        }
        String token = encryptionService.decrypt(owner.getAccessToken());
        String liveHead = gitHubService.getPullRequestHeadSha(token, repo.getFullName(), pr.getGithubPrNumber());
        if (!headSha.equals(liveHead)) {
            terminal(publication, attempt, ReviewPublication.SUPERSEDED,
                    "GitHub reports a newer PR head (" + liveHead + ") than the reviewed one");
            return;
        }

        String marker = ReviewCommentFormatter.summaryMarker(repo.getGithubId(), pr.getGithubPrNumber());
        // Findings a developer dismissed or marked false positive (carried over
        // from earlier pushes) are never resurfaced on GitHub.
        List<Finding> visible = findings.stream()
                .filter(f -> !ReviewService.SUPPRESSED_STATUSES.contains(f.getStatus()))
                .toList();
        String body = ReviewCommentFormatter.summary(marker, headSha, visible, pr.getQualityScore(),
                publication.getEngine(), findings.size() - visible.size());
        Long knownCommentId = publication.getCommentId() != null ? publication.getCommentId() : pr.getGithubCommentId();
        Runnable beforeWrite = () -> checkBeforeWrite(publication, attempt, token, repo, pr);
        GitHubService.IssueComment comment = upsertSummary(token, repo.getFullName(), pr.getGithubPrNumber(),
                marker, owner.getGithubId(), knownCommentId, body, beforeWrite);
        if (publicationRepository.recordComment(publication.getId(), attempt, comment.id(), comment.htmlUrl()) != 1) {
            log.warn("Publication {} attempt {} lost its lease after writing comment {}",
                    publication.getId(), attempt, comment.id());
            return;
        }

        Long reviewId = null;
        String inlineError = null;
        if (publication.isInlineComments()) {
            try {
                reviewId = publishInlineReview(token, repo, pr, owner.getGithubId(), headSha,
                        publication.getInlineFindingIds(), findings, beforeWrite);
            } catch (GitHubApiException ex) {
                if (ex.retryable()) {
                    throw ex;
                }
                // The summary is published; a rejected inline review (e.g. 422 for a
                // line GitHub does not consider part of the diff) must not undo that.
                inlineError = safe("inline review not posted: " + ex.describe());
            }
        }

        Long finalReviewId = reviewId;
        String message = inlineError;
        Boolean published = transactionTemplate.execute(status -> {
            if (publicationRepository.markPublished(publication.getId(), attempt, finalReviewId, message) != 1) {
                return false;
            }
            pullRequestRepository.updateGithubCommentId(pr.getId(), comment.id());
            return true;
        });
        if (Boolean.TRUE.equals(published)) {
            log.info("Published review of PR #{} head {} as comment {}", pr.getGithubPrNumber(), headSha, comment.id());
        } else {
            log.warn("Publication {} attempt {} lost its lease; result discarded", publication.getId(), attempt);
        }
    }

    /**
     * Edit the PR's summary comment, creating it only if no comment carrying
     * the marker (by the owner's account) exists. A stored id that GitHub no
     * longer knows (the comment was deleted) falls back to the marker lookup.
     */
    private GitHubService.IssueComment upsertSummary(String token, String repoFullName, int prNumber,
                                                     String marker, long ownerGithubId,
                                                     Long knownCommentId, String body, Runnable beforeWrite) {
        if (knownCommentId != null) {
            try {
                beforeWrite.run();
                return gitHubService.updateIssueComment(token, repoFullName, knownCommentId, body);
            } catch (GitHubApiException ex) {
                if (ex.kind() != GitHubApiException.Kind.NOT_FOUND) {
                    throw ex;
                }
                log.info("Summary comment {} on {}#{} is gone; looking it up by marker",
                        knownCommentId, repoFullName, prNumber);
            }
        }
        Optional<GitHubService.IssueComment> existing =
                gitHubService.findIssueComment(token, repoFullName, prNumber, marker, ownerGithubId);
        if (existing.isPresent()) {
            beforeWrite.run();
            return gitHubService.updateIssueComment(token, repoFullName, existing.get().id(), body);
        }
        beforeWrite.run();
        return gitHubService.createIssueComment(token, repoFullName, prNumber, body);
    }

    /** One review per head, found again by its marker; {@code null} when no finding is on an added line. */
    private Long publishInlineReview(String token, Repository repo, PullRequestEntity pr, long ownerGithubId,
                                     String headSha, String candidateIdsJson, List<Finding> findings,
                                     Runnable beforeWrite)
            throws GitHubApiException {
        String marker = ReviewCommentFormatter.reviewMarker(repo.getGithubId(), pr.getGithubPrNumber(), headSha);
        Optional<Long> existing = gitHubService.findReview(token, repo.getFullName(), pr.getGithubPrNumber(),
                marker, ownerGithubId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Map<String, Finding> byId = findings.stream()
                .collect(Collectors.toMap(f -> f.getId().toString(), Function.identity()));
        List<GitHubService.InlineComment> comments = new ArrayList<>();
        for (String id : parseIds(candidateIdsJson)) {
            Finding f = byId.get(id);
            if (f != null && f.getLineStart() != null) {
                comments.add(new GitHubService.InlineComment(f.getFilePath(), f.getLineStart(),
                        ReviewCommentFormatter.inlineComment(f)));
            }
        }
        if (comments.isEmpty()) {
            return null;
        }
        beforeWrite.run();
        return gitHubService.createReview(token, repo.getFullName(), pr.getGithubPrNumber(), headSha,
                ReviewCommentFormatter.reviewBody(marker, headSha, comments.size()), comments);
    }

    private List<String> parseIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception ex) {
            throw new IllegalStateException("inline finding ids are not a JSON string array", ex);
        }
    }

    private void checkBeforeWrite(ReviewPublication publication, int attempt, String token,
                                  Repository repo, PullRequestEntity pr) {
        if (publicationRepository.renewLease(publication.getId(), attempt, Instant.now().minus(LEASE)) != 1) {
            throw new LostPublicationLease();
        }
        String dbHead = pullRequestRepository.findHeadShaById(pr.getId()).orElse(null);
        if (!publication.getHeadSha().equals(dbHead)
                || !publication.getHeadSha().equals(gitHubService.getPullRequestHeadSha(
                        token, repo.getFullName(), pr.getGithubPrNumber()))) {
            throw new SupersededPublication("PR head moved before the GitHub write");
        }
    }

    private static final class SupersededPublication extends RuntimeException {
        SupersededPublication(String message) {
            super(message);
        }
    }

    private static final class LostPublicationLease extends RuntimeException {
    }

    private void terminal(ReviewPublication publication, int attempt, String status, String message) {
        if (publicationRepository.markTerminal(publication.getId(), attempt, status, safe(message)) != 1) {
            log.warn("Publication {} attempt {} lost its lease; '{}' discarded", publication.getId(), attempt, status);
        } else {
            log.info("Publication {} for head {}: {} ({})", publication.getId(), publication.getHeadSha(),
                    status, message);
        }
    }

    /** Backoff 30 s · 2^(attempt-1) (capped at 30 min) plus jitter, or GitHub's rate-limit reset. */
    private void retry(ReviewPublication publication, int attempt, Instant notBefore, String error) {
        long baseSeconds = Math.min(1800L, 30L << Math.min(attempt - 1, 6));
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, baseSeconds / 4L));
        Instant backoff = Instant.now().plusSeconds(baseSeconds + jitter);
        // A rate limit is honoured exactly (already capped by GitHubService); backoff does not stack on it.
        Instant retryAt = notBefore != null ? notBefore : backoff;
        if (publicationRepository.markRetry(publication.getId(), attempt, retryAt, error) != 1) {
            log.warn("Publication {} attempt {} lost its lease; failure not recorded", publication.getId(), attempt);
        }
    }

    private String safe(String message) {
        String redacted = secretRedactor.redact(message == null ? "publication failed" : message);
        return redacted.length() <= MAX_ERROR_CHARS ? redacted : redacted.substring(0, MAX_ERROR_CHARS);
    }
}
