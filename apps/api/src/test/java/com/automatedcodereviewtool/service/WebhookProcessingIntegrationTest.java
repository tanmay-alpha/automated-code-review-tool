package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.dto.MlFinding;
import com.automatedcodereviewtool.dto.MlReviewResponse;
import com.automatedcodereviewtool.entity.Finding;
import com.automatedcodereviewtool.entity.ProcessedWebhook;
import com.automatedcodereviewtool.entity.PullRequestEntity;
import com.automatedcodereviewtool.entity.Repository;
import com.automatedcodereviewtool.entity.ReviewPublication;
import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.exception.InvalidDiffException;
import com.automatedcodereviewtool.exception.MlWorkerException;
import com.automatedcodereviewtool.repository.FindingRepository;
import com.automatedcodereviewtool.repository.ProcessedWebhookRepository;
import com.automatedcodereviewtool.repository.PullRequestRepository;
import com.automatedcodereviewtool.repository.RepositoryRepository;
import com.automatedcodereviewtool.repository.ReviewPublicationRepository;
import com.automatedcodereviewtool.repository.UserRepository;
import com.automatedcodereviewtool.security.EncryptionService;
import com.automatedcodereviewtool.webhook.GitHubWebhookEvent;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Webhook delivery → review → GitHub publication against H2, the real
 * services and the real {@link GitHubService} talking HTTP to a stateful
 * fake GitHub ({@link FakeGitHub}). Only the ML worker and the repository
 * publication policy are mocked. Background pollers are slowed down so the
 * tests drive them explicitly.
 */
@SpringBootTest(properties = {
        "app.webhook.recovery-delay-ms=3600000",
        "app.outbox.poll-delay-ms=3600000",
        "app.publication.poll-delay-ms=3600000"
})
@ActiveProfiles("test")
class WebhookProcessingIntegrationTest {

    private static final AtomicLong GITHUB_IDS = new AtomicLong(990_000L);
    private static final String HEAD_A = "a".repeat(40);
    private static final String HEAD_B = "b".repeat(40);

    /**
     * The PR diff at {@code head}. Each hunk adds a line carrying the repo and
     * head, because the H2 schema keeps code_samples.content_sha256 (a digest
     * of the added code) globally unique. The flagged lines (2) are identical
     * across heads.
     */
    private static String diff(long repoGithubId, String head) {
        String token = repoGithubId + "-" + head.substring(0, 8);
        return """
                diff --git a/src/a.py b/src/a.py
                --- a/src/a.py
                +++ b/src/a.py
                @@ -1,1 +1,3 @@
                 import os
                +API_KEY = "sk-live-abcdefghijklmnopqrstuvwxyz123456"
                +BUILD = "%1$s"
                diff --git a/src/b.py b/src/b.py
                --- a/src/b.py
                +++ b/src/b.py
                @@ -1,1 +1,3 @@
                 import sys
                +print("hi")
                +# %1$s
                """.formatted(token);
    }

    private static final FakeGitHub GITHUB = new FakeGitHub();
    private static final MockWebServer SERVER = new MockWebServer();

    static {
        SERVER.setDispatcher(GITHUB);
        try {
            SERVER.start();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @DynamicPropertySource
    static void githubApi(DynamicPropertyRegistry registry) {
        registry.add("app.github.api-base-url", () -> SERVER.url("/").toString().replaceAll("/$", ""));
    }

    @AfterAll
    static void stopServer() throws IOException {
        SERVER.shutdown();
    }

    @Autowired private WebhookService webhookService;
    @Autowired private ReviewService reviewService;
    @Autowired private ReviewPublisher reviewPublisher;
    @Autowired private UserRepository userRepository;
    @Autowired private RepositoryRepository repositoryRepository;
    @Autowired private PullRequestRepository pullRequestRepository;
    @Autowired private FindingRepository findingRepository;
    @Autowired private ProcessedWebhookRepository processedWebhookRepository;
    @Autowired private ReviewPublicationRepository publicationRepository;
    @Autowired private EncryptionService encryptionService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.automatedcodereviewtool.controller.ReviewRunController reviewRunController;

    @MockBean private MlWorkerService mlWorkerService;
    @MockBean private ReviewPipelinePolicy publicationPolicy;

    private long githubId;
    private Repository repo;

    @BeforeEach
    void setUp() {
        // The pollers are global: park work left by earlier tests (this class and
        // others sharing the in-memory database) so each test only sees its own.
        Timestamp parked = Timestamp.from(Instant.now().plus(Duration.ofDays(365)));
        jdbc.update("UPDATE processed_webhooks SET available_at = ? WHERE status IN ('received', 'failed')", parked);
        jdbc.update("UPDATE review_publications SET available_at = ? WHERE status = 'pending'", parked);
        githubId = GITHUB_IDS.incrementAndGet();
        repo = saveRepo(githubId, true);
        long repoGithubId = githubId;
        GITHUB.reset(githubId, HEAD_A, head -> diff(repoGithubId, head));
        when(publicationPolicy.postComments(any())).thenReturn(true);
        when(publicationPolicy.inlineComments(any())).thenReturn(false);
        when(publicationPolicy.apply(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(mlWorkerService.review(anyString(), anyString())).thenReturn(twoFindings());
    }

    // -----------------------------------------------------------------
    // Happy path and idempotent ingress
    // -----------------------------------------------------------------

    @Test
    void deliveryIsReviewedAndPublishedAsOneStickyComment() {
        deliver("ok-" + githubId, HEAD_A);

        PullRequestEntity pr = pr();
        assertThat(pr.getStatus()).isEqualTo("reviewed");
        assertThat(pr.getQualityScore()).isEqualByComparingTo("78.90");
        assertThat(findingRepository.findAllByPullRequestId(pr.getId()))
                .extracting(Finding::getFilePath, Finding::getAntiPattern)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("src/b.py", "MAINTAINABILITY_PRINT_STATEMENT"),
                        org.assertj.core.groups.Tuple.tuple("src/a.py", "SECURITY_HARDCODED_SECRET"));
        assertThat(repositoryRepository.findById(repo.getId()).orElseThrow().getQualityScore())
                .isEqualByComparingTo("78.90");

        assertThat(GITHUB.comments).hasSize(1);
        String body = (String) GITHUB.comments.get(0).get("body");
        assertThat(body).startsWith(ReviewCommentFormatter.summaryMarker(githubId, 7))
                .contains("detector: rule-based", "SECURITY_HARDCODED_SECRET", "aaaaaaaaaaaa");
        // The repo full name was sent as two path segments, not "owner%2Frepo".
        assertThat(GITHUB.requests).anyMatch(r -> r.startsWith("POST /repos/owner/repo-" + githubId + "/issues/7/comments"));

        ProcessedWebhook delivery = processedWebhookRepository.findById("ok-" + githubId).orElseThrow();
        assertThat(delivery.getStatus()).isEqualTo("completed");
        assertThat(delivery.getLastError()).isNull();
        ReviewPublication publication = publication("ok-" + githubId);
        assertThat(publication.getStatus()).isEqualTo(ReviewPublication.PUBLISHED);
        assertThat(publication.getPublishedHeadSha()).isEqualTo(HEAD_A);
        long commentId = ((Number) GITHUB.comments.get(0).get("id")).longValue();
        assertThat(publication.getCommentId()).isEqualTo(commentId);
        assertThat(publication.getCommentUrl()).isEqualTo(GITHUB.comments.get(0).get("html_url"));
        assertThat(pr().getGithubCommentId()).isEqualTo(commentId);
    }

    /** Scenario: duplicate delivery ID. */
    @Test
    void duplicateDeliveryIdIsAcceptedOnce() {
        deliver("dup-" + githubId, HEAD_A);

        assertThat(webhookService.receiveDelivery("dup-" + githubId, event(HEAD_A), githubId)).isFalse();
        webhookService.processDelivery("dup-" + githubId);

        assertThat(GITHUB.calls("POST comment")).isEqualTo(1);
        assertThat(GITHUB.calls("GET diff")).isEqualTo(1);
        assertThat(GITHUB.comments).hasSize(1);
    }

    /** Scenario: the same head SHA redelivered under a new delivery ID. */
    @Test
    void sameHeadRedeliveredUnderANewDeliveryIdIsIgnored() {
        deliver("first-" + githubId, HEAD_A);

        assertThat(webhookService.receiveDelivery("second-" + githubId, event(HEAD_A), githubId)).isFalse();

        assertThat(processedWebhookRepository.findById("second-" + githubId)).isEmpty();
        assertThat(GITHUB.calls("GET diff")).isEqualTo(1);
        assertThat(GITHUB.comments).hasSize(1);
    }

    @Test
    void deliveryForDisconnectedRepoIsIgnored() {
        long otherId = GITHUB_IDS.incrementAndGet();
        saveRepo(otherId, false);

        assertThat(webhookService.receiveDelivery("off-" + otherId, event(HEAD_B), otherId)).isFalse();
        assertThat(processedWebhookRepository.findById("off-" + otherId)).isEmpty();
    }

    // -----------------------------------------------------------------
    // Head re-checks: stale findings are never published
    // -----------------------------------------------------------------

    @Test
    void newPushEditsTheStickyCommentInsteadOfPostingAnother() {
        deliver("push1-" + githubId, HEAD_A);
        GITHUB.headSha = HEAD_B;
        deliver("push2-" + githubId, HEAD_B);

        assertThat(GITHUB.comments).hasSize(1);
        assertThat(GITHUB.calls("POST comment")).isEqualTo(1);
        assertThat(GITHUB.calls("PATCH comment")).isEqualTo(1);
        assertThat((String) GITHUB.comments.get(0).get("body")).contains("bbbbbbbbbbbb").doesNotContain("aaaaaaaaaaaa");
        assertThat(publication("push2-" + githubId).getPublishedHeadSha()).isEqualTo(HEAD_B);
    }

    /** Scenario: a new commit is pushed while the old head is being analysed. */
    @Test
    void newCommitDuringAnalysisSupersedesTheOldReview() {
        String oldDelivery = "old-" + githubId;
        String newDelivery = "new-" + githubId;
        when(mlWorkerService.review(anyString(), anyString())).thenAnswer(inv -> {
            // The push lands mid-analysis: GitHub now reports head B and its webhook is accepted.
            GITHUB.headSha = HEAD_B;
            assertThat(webhookService.receiveDelivery(newDelivery, event(HEAD_B), githubId)).isTrue();
            return twoFindings();
        });

        deliver(oldDelivery, HEAD_A);

        ProcessedWebhook old = processedWebhookRepository.findById(oldDelivery).orElseThrow();
        assertThat(old.getStatus()).isEqualTo("superseded");
        assertThat(publicationRepository.findByDeliveryId(oldDelivery)).isEmpty();
        assertThat(GITHUB.comments).isEmpty();
        // The stale run did not revert the PR to the old head or mark it reviewed.
        assertThat(pr().getHeadSha()).isEqualTo(HEAD_B);
        assertThat(pr().getStatus()).isEqualTo("processing");
        assertThat(findingRepository.findAllByPullRequestId(pr().getId())).isEmpty();

        org.mockito.Mockito.doReturn(twoFindings()).when(mlWorkerService).review(anyString(), anyString());
        webhookService.processDelivery(newDelivery);

        assertThat(processedWebhookRepository.findById(newDelivery).orElseThrow().getStatus()).isEqualTo("completed");
        assertThat(GITHUB.comments).hasSize(1);
        assertThat((String) GITHUB.comments.get(0).get("body")).contains("bbbbbbbbbbbb");
        assertThat(publication(newDelivery).getStatus()).isEqualTo(ReviewPublication.PUBLISHED);
    }

    @Test
    void publicationIsSupersededWhenGitHubReportsANewerHead() {
        // The push happened on GitHub but its webhook has not arrived yet.
        GITHUB.onDiffServed = () -> GITHUB.headSha = HEAD_B;

        deliver("racing-" + githubId, HEAD_A);

        ReviewPublication publication = publication("racing-" + githubId);
        assertThat(publication.getStatus()).isEqualTo(ReviewPublication.SUPERSEDED);
        assertThat(publication.getLastError()).contains(HEAD_B);
        assertThat(publication.getPublishedHeadSha()).isNull();
        assertThat(GITHUB.calls("POST comment")).isZero();
        assertThat(GITHUB.calls("PATCH comment")).isZero();
        // The analysis itself completed; only publication was withheld.
        assertThat(processedWebhookRepository.findById("racing-" + githubId).orElseThrow().getStatus())
                .isEqualTo("completed");
    }

    // -----------------------------------------------------------------
    // Stable external-effect identity
    // -----------------------------------------------------------------

    @Test
    void headChangingDuringMarkerLookupPreventsTheExternalWrite() {
        GITHUB.onCommentsListed = () -> GITHUB.headSha = HEAD_B;
        deliver("lookup-head-" + githubId, HEAD_A);
        assertThat(publication("lookup-head-" + githubId).getStatus()).isEqualTo(ReviewPublication.SUPERSEDED);
        assertThat(GITHUB.calls("POST comment")).isZero();
        assertThat(GITHUB.comments).isEmpty();
    }

    @Test
    void databaseHeadChangingDuringMarkerLookupPreventsTheExternalWrite() {
        GITHUB.onCommentsListed = () -> jdbc.update("UPDATE pull_requests SET head_sha = ? WHERE repo_id = ?",
                HEAD_B, repo.getId());
        deliver("lookup-db-" + githubId, HEAD_A);
        assertThat(publication("lookup-db-" + githubId).getStatus()).isEqualTo(ReviewPublication.SUPERSEDED);
        assertThat(GITHUB.calls("POST comment")).isZero();
    }

    @Test
    void expiredPublisherStopsBeforeWritingAndRecoveryUsesTheMarker() {
        String delivery = "expired-pub-" + githubId;
        GITHUB.onCommentsListed = () -> jdbc.update(
                "UPDATE review_publications SET processing_started_at = ? WHERE delivery_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))), delivery);
        deliver(delivery, HEAD_A);
        assertThat(GITHUB.calls("POST comment")).isZero();
        assertThat(publication(delivery).getStatus()).isEqualTo(ReviewPublication.PENDING);
        GITHUB.onCommentsListed = () -> { };
        reviewPublisher.publish(publication(delivery).getId());
        assertThat(publication(delivery).getStatus()).isEqualTo(ReviewPublication.PUBLISHED);
        assertThat(GITHUB.comments).hasSize(1);
    }

    /** Scenario: GitHub created the comment but the acknowledgement was lost. */
    @Test
    void lostAckOnCreateIsFollowedByAPatchOfTheMarkerComment() {
        GITHUB.fail("POST comment", 1, FakeGitHub.Fault.lostAck());

        deliver("ack-" + githubId, HEAD_A);

        ReviewPublication afterFirst = publication("ack-" + githubId);
        assertThat(afterFirst.getStatus()).isEqualTo(ReviewPublication.PENDING);
        assertThat(afterFirst.getAttemptCount()).isEqualTo(1);
        assertThat(afterFirst.getCommentId()).isNull();
        assertThat(afterFirst.getLastError()).startsWith("github_unavailable");
        assertThat(afterFirst.getAvailableAt()).isAfter(Instant.now());
        assertThat(GITHUB.comments).hasSize(1); // GitHub did create it
        // The delivery is complete: publication retries on its own.
        assertThat(processedWebhookRepository.findById("ack-" + githubId).orElseThrow().getStatus())
                .isEqualTo("completed");

        makeDue(afterFirst);
        reviewPublisher.publishDue();

        assertThat(GITHUB.comments).hasSize(1);
        assertThat(GITHUB.calls("POST comment")).isEqualTo(1);
        assertThat(GITHUB.calls("PATCH comment")).isEqualTo(1);
        ReviewPublication published = publication("ack-" + githubId);
        assertThat(published.getStatus()).isEqualTo(ReviewPublication.PUBLISHED);
        assertThat(published.getCommentId()).isEqualTo(((Number) GITHUB.comments.get(0).get("id")).longValue());
    }

    @Test
    void markerLookupPagesThroughLongConversations() {
        // 120 comments by other people, then our summary from an earlier run
        // whose id was never recorded (e.g. the database was restored).
        for (int i = 0; i < 120; i++) {
            GITHUB.seedComment(42L, "LGTM " + i);
        }
        Map<String, Object> ours = GITHUB.seedComment(githubId,
                ReviewCommentFormatter.summaryMarker(githubId, 7) + "\nold summary");

        deliver("paged-" + githubId, HEAD_A);

        assertThat(GITHUB.calls("POST comment")).isZero();
        assertThat(GITHUB.calls("GET comments")).isEqualTo(3); // 50 + 50 + 21
        assertThat((String) ours.get("body")).contains("SECURITY_HARDCODED_SECRET");
        assertThat(publication("paged-" + githubId).getCommentId()).isEqualTo(((Number) ours.get("id")).longValue());
    }

    @Test
    void markerPlantedByAnotherUserIsNeverEdited() {
        Map<String, Object> planted = GITHUB.seedComment(31337L,
                ReviewCommentFormatter.summaryMarker(githubId, 7) + "\nfake summary");

        deliver("spoof-" + githubId, HEAD_A);

        assertThat(planted.get("body")).isEqualTo(ReviewCommentFormatter.summaryMarker(githubId, 7) + "\nfake summary");
        assertThat(GITHUB.calls("PATCH comment")).isZero();
        assertThat(GITHUB.calls("POST comment")).isEqualTo(1);
    }

    @Test
    void dismissedFindingIsNotResurfacedOnTheNextPush() {
        deliver("triage1-" + githubId, HEAD_A);
        jdbc.update("UPDATE findings SET status = 'dismissed' WHERE pr_id = ? AND anti_pattern = ?",
                pr().getId(), "SECURITY_HARDCODED_SECRET");
        GITHUB.headSha = HEAD_B;

        deliver("triage2-" + githubId, HEAD_B); // same code: the worker reports the same findings

        assertThat(findingRepository.findAllByPullRequestId(pr().getId()))
                .filteredOn(f -> "SECURITY_HARDCODED_SECRET".equals(f.getAntiPattern()))
                .singleElement().extracting(Finding::getStatus).isEqualTo("dismissed");
        String body = (String) GITHUB.comments.get(0).get("body");
        assertThat(body).contains("MAINTAINABILITY_PRINT_STATEMENT", "1 finding(s) dismissed")
                .doesNotContain("SECURITY_HARDCODED_SECRET");
    }

    @Test
    void deletedStickyCommentIsRecreated() {
        deliver("del1-" + githubId, HEAD_A);
        GITHUB.comments.clear(); // a maintainer deleted it
        GITHUB.headSha = HEAD_B;

        deliver("del2-" + githubId, HEAD_B);

        assertThat(GITHUB.comments).hasSize(1);
        assertThat(GITHUB.calls("POST comment")).isEqualTo(2);
        assertThat(publication("del2-" + githubId).getStatus()).isEqualTo(ReviewPublication.PUBLISHED);
    }

    /** Scenario: crash after the review transaction committed, before any comment was posted. */
    @Test
    void crashAfterReviewCommitIsPublishedByThePollerExactlyOnce() {
        String deliveryId = "crash-" + githubId;
        assertThat(webhookService.receiveDelivery(deliveryId, event(HEAD_A), githubId)).isTrue();
        assertThat(processedWebhookRepository.claim(deliveryId, 0)).isEqualTo(1);
        // The review commits; the process dies before marking the delivery or publishing.
        assertThat(reviewService.orchestrateReview(pr(), diff(githubId, HEAD_A),
                new ReviewRun(deliveryId, 1, HEAD_A, true, false)).success()).isTrue();
        assertThat(publication(deliveryId).getStatus()).isEqualTo(ReviewPublication.PENDING);
        assertThat(GITHUB.comments).isEmpty();

        reviewPublisher.publishDue();
        reviewPublisher.publishDue();

        assertThat(GITHUB.comments).hasSize(1);
        assertThat(GITHUB.calls("POST comment")).isEqualTo(1);
        assertThat(publication(deliveryId).getStatus()).isEqualTo(ReviewPublication.PUBLISHED);

        // After restart the stuck delivery is recovered and re-reviewed: the
        // re-queued publication edits the same comment.
        expireLease(deliveryId);
        webhookService.processDurableBacklog();

        assertThat(processedWebhookRepository.findById(deliveryId).orElseThrow().getStatus()).isEqualTo("completed");
        assertThat(GITHUB.comments).hasSize(1);
        assertThat(GITHUB.calls("POST comment")).isEqualTo(1);
        assertThat(publication(deliveryId).getStatus()).isEqualTo(ReviewPublication.PUBLISHED);
    }

    /** Scenario: redelivery after a restart that interrupted the analysis itself. */
    @Test
    void deliveryInterruptedByRestartIsRecoveredAndPublishedOnce() {
        String deliveryId = "restart-" + githubId;
        assertThat(webhookService.receiveDelivery(deliveryId, event(HEAD_A), githubId)).isTrue();
        assertThat(processedWebhookRepository.claim(deliveryId, 0)).isEqualTo(1);
        expireLease(deliveryId); // the worker died mid-analysis

        webhookService.processDurableBacklog();

        ProcessedWebhook delivery = processedWebhookRepository.findById(deliveryId).orElseThrow();
        assertThat(delivery.getStatus()).isEqualTo("completed");
        assertThat(delivery.getAttemptCount()).isEqualTo(2);
        assertThat(GITHUB.comments).hasSize(1);
        assertThat(publication(deliveryId).getStatus()).isEqualTo(ReviewPublication.PUBLISHED);
    }

    // -----------------------------------------------------------------
    // Inline review
    // -----------------------------------------------------------------

    @Test
    void inlineReviewIsPostedOncePerHeadOnAddedLinesOnly() {
        when(publicationPolicy.inlineComments(any())).thenReturn(true);
        when(mlWorkerService.review(anyString(), anyString())).thenReturn(new MlReviewResponse(List.of(
                new MlFinding("src/a.py", null, 2, 2, "SECURITY_HARDCODED_SECRET", "SECURITY", "critical",
                        new BigDecimal("0.95"), "hard-coded key", "API_KEY = \"<REDACTED_SECRET>\"",
                        "Load it from the environment.", null),
                // Context line (unchanged code): summary only.
                new MlFinding("src/b.py", null, 1, 1, "MAINTAINABILITY_PRINT_STATEMENT", "MAINTAINABILITY",
                        "minor", new BigDecimal("0.7"), "context line")),
                new BigDecimal("80.00"), 5, 2, "fallback", "rules-v1", "1.0.0"));
        GITHUB.fail("POST review", 1, FakeGitHub.Fault.lostAck());

        deliver("inline-" + githubId, HEAD_A);
        ReviewPublication first = publication("inline-" + githubId);
        assertThat(first.getStatus()).isEqualTo(ReviewPublication.PENDING);
        assertThat(first.getCommentId()).isNotNull(); // summary recorded before the inline step
        makeDue(first);
        reviewPublisher.publishDue();

        assertThat(GITHUB.reviews).hasSize(1);
        assertThat(GITHUB.calls("POST review")).isEqualTo(1);
        Map<String, Object> review = GITHUB.reviews.get(0);
        assertThat(review.get("commit_id")).isEqualTo(HEAD_A);
        assertThat((String) review.get("body")).startsWith(ReviewCommentFormatter.reviewMarker(githubId, 7, HEAD_A));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> comments = (List<Map<String, Object>>) review.get("comments");
        assertThat(comments).singleElement().satisfies(c -> {
            assertThat(c.get("path")).isEqualTo("src/a.py");
            assertThat(c.get("line")).isEqualTo(2);
            assertThat(c.get("side")).isEqualTo("RIGHT");
            assertThat((String) c.get("body")).contains("Load it from the environment");
        });
        // The summary was edited, not duplicated, by the retry.
        assertThat(GITHUB.comments).hasSize(1);
        ReviewPublication published = publication("inline-" + githubId);
        assertThat(published.getStatus()).isEqualTo(ReviewPublication.PUBLISHED);
        assertThat(published.getReviewId()).isEqualTo(((Number) review.get("id")).longValue());
    }

    @Test
    void disabledCommentsSkipPublicationWithoutCallingGitHub() {
        when(publicationPolicy.postComments(any())).thenReturn(false);

        deliver("quiet-" + githubId, HEAD_A);

        assertThat(publication("quiet-" + githubId).getStatus()).isEqualTo(ReviewPublication.SKIPPED);
        assertThat(GITHUB.calls("GET head")).isZero();
        assertThat(GITHUB.comments).isEmpty();
        assertThat(pr().getStatus()).isEqualTo("reviewed");
    }

    // -----------------------------------------------------------------
    // Typed GitHub failures
    // -----------------------------------------------------------------

    /** Scenario: the owner revoked the OAuth token. */
    @Test
    void revokedTokenDeadLettersTheDeliveryWithAnExplainableReason() {
        GITHUB.fail("GET diff", 1, FakeGitHub.Fault.respond(FakeGitHub.status(401)));

        deliver("revoked-" + githubId, HEAD_A);

        ProcessedWebhook delivery = processedWebhookRepository.findById("revoked-" + githubId).orElseThrow();
        assertThat(delivery.getStatus()).isEqualTo("dead_letter");
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getLastError()).startsWith("github_auth_revoked");
        assertThat(pr().getStatus()).isEqualTo("failed");
        assertThat(pr().getErrorMessage()).startsWith("github_auth_revoked");
        verify(mlWorkerService, never()).review(anyString(), anyString());
        assertThat(processedWebhookRepository.findEligibleDeliveryIds(1000)).doesNotContain("revoked-" + githubId);
    }

    @Test
    void revokedTokenAtPublicationFailsThePublicationNotTheReview() {
        GITHUB.fail("GET head", 1, FakeGitHub.Fault.respond(FakeGitHub.status(401)));

        deliver("revoked-pub-" + githubId, HEAD_A);

        ReviewPublication publication = publication("revoked-pub-" + githubId);
        assertThat(publication.getStatus()).isEqualTo(ReviewPublication.FAILED);
        assertThat(publication.getLastError()).startsWith("github_auth_revoked");
        assertThat(pr().getStatus()).isEqualTo("reviewed");
        assertThat(publicationRepository.findDueIds(Instant.now(), 1000)).doesNotContain(publication.getId().toString());
    }

    @Test
    void diffTooLargeAndMissingPullRequestAreTerminal() {
        GITHUB.fail("GET diff", 1, FakeGitHub.Fault.respond(FakeGitHub.status(406)));
        deliver("huge-" + githubId, HEAD_A);
        ProcessedWebhook huge = processedWebhookRepository.findById("huge-" + githubId).orElseThrow();
        assertThat(huge.getStatus()).isEqualTo("dead_letter");
        assertThat(huge.getLastError()).startsWith("diff_too_large");
        assertThat(pr().getErrorMessage()).startsWith("diff_too_large");

        GITHUB.fail("GET diff", 1, FakeGitHub.Fault.respond(FakeGitHub.status(404)));
        deliver("gone-" + githubId, HEAD_B);
        ProcessedWebhook gone = processedWebhookRepository.findById("gone-" + githubId).orElseThrow();
        assertThat(gone.getStatus()).isEqualTo("dead_letter");
        assertThat(gone.getLastError()).startsWith("github_not_found");
    }

    /** Scenario: primary rate limit while fetching the diff. */
    @Test
    void rateLimitedDiffFetchRetriesAtTheReset() {
        long reset = Instant.now().plusSeconds(600).getEpochSecond();
        GITHUB.fail("GET diff", 1, FakeGitHub.Fault.respond(FakeGitHub.status(403,
                "x-ratelimit-remaining", "0", "x-ratelimit-reset", Long.toString(reset))));

        deliver("limited-" + githubId, HEAD_A);

        ProcessedWebhook delivery = processedWebhookRepository.findById("limited-" + githubId).orElseThrow();
        assertThat(delivery.getStatus()).isEqualTo("failed");
        assertThat(delivery.getLastError()).startsWith("github_rate_limited");
        assertThat(delivery.getAvailableAt()).isEqualTo(Instant.ofEpochSecond(reset));
        assertThat(processedWebhookRepository.findEligibleDeliveryIds(1000)).doesNotContain("limited-" + githubId);
    }

    /** Scenario: secondary rate limit while publishing. */
    @Test
    void rateLimitedPublicationWaitsForRetryAfterWithoutTouchingTheReview() {
        GITHUB.fail("POST comment", 1, FakeGitHub.Fault.respond(FakeGitHub.status(429, "Retry-After", "120")));
        Instant before = Instant.now();

        deliver("limited-pub-" + githubId, HEAD_A);

        ReviewPublication publication = publication("limited-pub-" + githubId);
        assertThat(publication.getStatus()).isEqualTo(ReviewPublication.PENDING);
        assertThat(publication.getLastError()).startsWith("github_rate_limited");
        assertThat(publication.getAvailableAt())
                .isBetween(before.plusSeconds(119), Instant.now().plusSeconds(121));
        assertThat(publicationRepository.findDueIds(Instant.now(), 1000)).doesNotContain(publication.getId().toString());
        assertThat(processedWebhookRepository.findById("limited-pub-" + githubId).orElseThrow().getStatus())
                .isEqualTo("completed");
        assertThat(GITHUB.comments).isEmpty();
    }

    @Test
    void githubServerErrorSchedulesARetryWithBackoff() {
        GITHUB.fail("GET diff", 1, FakeGitHub.Fault.respond(FakeGitHub.status(502)));

        deliver("bad-gateway-" + githubId, HEAD_A);

        ProcessedWebhook delivery = processedWebhookRepository.findById("bad-gateway-" + githubId).orElseThrow();
        assertThat(delivery.getStatus()).isEqualTo("failed");
        assertThat(delivery.getLastError()).startsWith("github_unavailable");
        assertThat(delivery.getAvailableAt()).isAfter(Instant.now());

        jdbc.update("UPDATE processed_webhooks SET available_at = ? WHERE delivery_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), "bad-gateway-" + githubId);
        webhookService.processDelivery("bad-gateway-" + githubId);

        assertThat(processedWebhookRepository.findById("bad-gateway-" + githubId).orElseThrow().getStatus())
                .isEqualTo("completed");
        assertThat(GITHUB.comments).hasSize(1);
    }

    // -----------------------------------------------------------------
    // ML worker failures
    // -----------------------------------------------------------------

    /** Scenario: ML worker unavailable. */
    @Test
    void unavailableWorkerRetriesTheDeliveryAndPublishesNothing() {
        when(mlWorkerService.review(anyString(), anyString()))
                .thenThrow(new MlWorkerException("ML worker unavailable"));

        deliver("ml-down-" + githubId, HEAD_A);

        ProcessedWebhook delivery = processedWebhookRepository.findById("ml-down-" + githubId).orElseThrow();
        assertThat(delivery.getStatus()).isEqualTo("failed");
        assertThat(delivery.getLastError()).contains("ML worker unavailable");
        assertThat(pr().getStatus()).isEqualTo("failed");
        assertThat(publicationRepository.findByDeliveryId("ml-down-" + githubId)).isEmpty();
        assertThat(GITHUB.calls("GET head")).isZero();
    }

    @Test
    void workerRejectionDeadLettersDeliveryWithoutRetry() {
        when(mlWorkerService.review(anyString(), anyString()))
                .thenThrow(new InvalidDiffException(422, "ML worker rejected diff: too large"));

        deliver("ml-422-" + githubId, HEAD_A);

        ProcessedWebhook delivery = processedWebhookRepository.findById("ml-422-" + githubId).orElseThrow();
        assertThat(delivery.getStatus()).isEqualTo("dead_letter");
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getLastError()).contains("too large");
        assertThat(pr().getStatus()).isEqualTo("failed");
        assertThat(processedWebhookRepository.findEligibleDeliveryIds(100)).doesNotContain("ml-422-" + githubId);
    }

    // -----------------------------------------------------------------
    // Leases
    // -----------------------------------------------------------------

    /** Scenario: two workers compete for the same delivery and the same publication. */
    @Test
    void competingWorkersGetOneLeaseEach() {
        String deliveryId = "lease-" + githubId;
        assertThat(webhookService.receiveDelivery(deliveryId, event(HEAD_A), githubId)).isTrue();
        assertThat(processedWebhookRepository.claim(deliveryId, 0)).isEqualTo(1);
        assertThat(processedWebhookRepository.claim(deliveryId, 0)).isZero();

        ReviewRun run = new ReviewRun(deliveryId, 1, HEAD_A, true, false);
        java.util.UUID publicationId = reviewService.orchestrateReview(pr(), diff(githubId, HEAD_A), run).publicationId();
        Instant staleBefore = Instant.now().minus(ReviewPublisher.LEASE);
        assertThat(publicationRepository.claim(publicationId, 0, staleBefore)).isEqualTo(1);
        assertThat(publicationRepository.claim(publicationId, 0, staleBefore)).isZero();
        assertThat(publicationRepository.claim(publicationId, 1, staleBefore)).isZero(); // lease is live
        // A stale publisher's late result is fenced out once the lease moved on.
        jdbc.update("UPDATE review_publications SET processing_started_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))), publicationId);
        assertThat(publicationRepository.claim(publicationId, 1, staleBefore)).isEqualTo(1);
        assertThat(publicationRepository.markPublished(publicationId, 1, null, null)).isZero();
        assertThat(publicationRepository.markPublished(publicationId, 2, null, null)).isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // GET /api/reviews/{prId}/runs
    // -----------------------------------------------------------------

    @Test
    void reviewRunsListEveryDeliveryWithItsPublicationNewestFirst() {
        GITHUB.fail("GET diff", 1, FakeGitHub.Fault.respond(FakeGitHub.status(401)));
        deliver("run1-" + githubId, HEAD_A);
        GITHUB.headSha = HEAD_B;
        deliver("run2-" + githubId, HEAD_B);
        User owner = repositoryRepository.findWithOwnerById(repo.getId()).orElseThrow().getOwner();

        List<com.automatedcodereviewtool.dto.ReviewRunResponse> runs =
                reviewRunController.getRuns(pr().getId(), owner).getBody();

        assertThat(runs).extracting(com.automatedcodereviewtool.dto.ReviewRunResponse::deliveryId)
                .containsExactly("run2-" + githubId, "run1-" + githubId);
        var latest = runs.get(0);
        assertThat(latest.status()).isEqualTo("completed");
        assertThat(latest.headSha()).isEqualTo(HEAD_B);
        assertThat(latest.attemptCount()).isEqualTo(1);
        assertThat(latest.completedAt()).isNotNull();
        assertThat(latest.publication().status()).isEqualTo(ReviewPublication.PUBLISHED);
        assertThat(latest.publication().publishedHeadSha()).isEqualTo(HEAD_B);
        assertThat(latest.publication().commentUrl()).isEqualTo(GITHUB.comments.get(0).get("html_url"));
        var first = runs.get(1);
        assertThat(first.status()).isEqualTo("dead_letter");
        assertThat(first.lastError()).startsWith("github_auth_revoked");
        assertThat(first.publication()).isNull();
    }

    @Test
    void reviewRunsAreHiddenFromOtherUsers() {
        deliver("private-" + githubId, HEAD_A);
        User stranger = userRepository.save(User.builder()
                .githubId(GITHUB_IDS.incrementAndGet())
                .githubUsername("stranger-" + githubId)
                .accessToken(encryptionService.encrypt("stranger-test-token"))
                .build());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> reviewRunController.getRuns(pr().getId(), stranger))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> reviewRunController.getRuns(java.util.UUID.randomUUID(), stranger))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private void deliver(String deliveryId, String headSha) {
        assertThat(webhookService.receiveDelivery(deliveryId, event(headSha), githubId)).isTrue();
        webhookService.processDelivery(deliveryId);
    }

    private PullRequestEntity pr() {
        return pullRequestRepository.findByRepoAndGithubPrNumber(repo, 7).orElseThrow();
    }

    private ReviewPublication publication(String deliveryId) {
        return publicationRepository.findByDeliveryId(deliveryId).orElseThrow();
    }

    private void makeDue(ReviewPublication publication) {
        jdbc.update("UPDATE review_publications SET available_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), publication.getId());
    }

    private void expireLease(String deliveryId) {
        jdbc.update("UPDATE processed_webhooks SET processing_started_at = ? WHERE delivery_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(20))), deliveryId);
    }

    private static MlReviewResponse twoFindings() {
        return new MlReviewResponse(
                List.of(
                        // Line 2 exists in both files; filePath decides.
                        new MlFinding("src/b.py", null, 2, 2, "MAINTAINABILITY_PRINT_STATEMENT",
                                "MAINTAINABILITY", "minor", new BigDecimal("0.7"), "print left in code",
                                "print(\"hi\")", "Use logging.", null),
                        new MlFinding("src/a.py", null, 2, 2, "SECURITY_HARDCODED_SECRET",
                                "SECURITY", "critical", new BigDecimal("0.95"), "hard-coded key",
                                "API_KEY = \"<REDACTED_SECRET>\"", "Load it from the environment.",
                                "Test fixtures with fake keys are fine.")),
                new BigDecimal("78.90"), 5, 2, "fallback", "rules-v1", "1.0.0");
    }

    private Repository saveRepo(long id, boolean active) {
        User owner = userRepository.save(User.builder()
                .githubId(id)
                .githubUsername("owner-" + id)
                .accessToken(encryptionService.encrypt("gh-token"))
                .build());
        return repositoryRepository.save(Repository.builder()
                .githubId(id)
                .fullName("owner/repo-" + id)
                .owner(owner)
                .webhookId(1L)
                .webhookSecret(encryptionService.encrypt("secret"))
                .isActive(active)
                .build());
    }

    private GitHubWebhookEvent event(String headSha) {
        return new GitHubWebhookEvent(
                "opened",
                new GitHubWebhookEvent.PrPayload(
                        7, "Add config",
                        new GitHubWebhookEvent.UserPayload("bob"),
                        new GitHubWebhookEvent.HeadPayload(headSha),
                        null,
                        "https://github.com/owner/repo/pull/7"),
                new GitHubWebhookEvent.RepoPayload(githubId, "owner/repo-" + githubId));
    }
}
