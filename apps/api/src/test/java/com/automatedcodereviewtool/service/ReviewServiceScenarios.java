package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.dto.MlFinding;
import com.automatedcodereviewtool.dto.MlReviewResponse;
import com.automatedcodereviewtool.dto.ReviewResult;
import com.automatedcodereviewtool.entity.Finding;
import com.automatedcodereviewtool.entity.IngestionOutbox;
import com.automatedcodereviewtool.entity.PullRequestEntity;
import com.automatedcodereviewtool.entity.Repository;
import com.automatedcodereviewtool.entity.ReviewPublication;
import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.repository.FindingRepository;
import com.automatedcodereviewtool.repository.IngestionOutboxRepository;
import com.automatedcodereviewtool.repository.ProcessedWebhookRepository;
import com.automatedcodereviewtool.repository.PullRequestRepository;
import com.automatedcodereviewtool.repository.RepositoryRepository;
import com.automatedcodereviewtool.repository.ReviewPublicationRepository;
import com.automatedcodereviewtool.repository.UserRepository;
import com.automatedcodereviewtool.security.EncryptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReviewService#orchestrateReview} against a real database with the
 * ML worker mocked; repository settings go through the real
 * {@link RepoReviewPolicy}. Run on H2 ({@link ReviewServiceIntegrationTest}) and
 * on Postgres ({@link ReviewServicePostgresTest}), where the findings /
 * prediction_events CHECK constraints are actually enforced.
 */
abstract class ReviewServiceScenarios {

    /** Unique per PR: the H2 schema keeps code_samples.content_sha256 globally unique. */
    private static String diff(PullRequestEntity pr) {
        return """
                diff --git a/src/a.py b/src/a.py
                --- a/src/a.py
                +++ b/src/a.py
                @@ -1,1 +1,3 @@
                 import os
                +API_KEY = "sk-live-abcdefghijklmnopqrstuvwxyz123456"
                +print("%s")
                """.formatted(pr.getHeadSha().substring(0, 12)); // short: 32+ char literals get redacted
    }

    @Autowired private ReviewService reviewService;
    @Autowired private UserRepository userRepository;
    @Autowired private RepositoryRepository repositoryRepository;
    @Autowired private PullRequestRepository pullRequestRepository;
    @Autowired private FindingRepository findingRepository;
    @Autowired private IngestionOutboxRepository outboxRepository;
    @Autowired private ProcessedWebhookRepository processedWebhookRepository;
    @Autowired private ReviewPublicationRepository publicationRepository;
    @Autowired private EncryptionService encryptionService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private MlWorkerService mlWorkerService;
    @MockBean private GitHubService gitHubService;

    @Test
    void findingCategoryAndSeverityComeFromTheTaxonomy_andUnknownPatternsAreRejected() {
        PullRequestEntity pr = savePullRequest();
        when(mlWorkerService.review(anyString(), anyString())).thenReturn(response("fallback",
                // Known pattern, but category/severity outside the DB CHECK constraints.
                new MlFinding("src/a.py", null, 2, 2, "SECURITY_HARDCODED_SECRET",
                        "security-ish", "BLOCKER", new BigDecimal("0.9"), "hard-coded key"),
                // Unknown pattern with equally bad values: rejected, not persisted.
                new MlFinding("src/a.py", null, 3, 3, "NOT_IN_TAXONOMY",
                        "x".repeat(50), "fatal", new BigDecimal("0.5"), "?")));

        ReviewResult result = reviewService.orchestrateReview(pr, diff(pr), run(pr));

        assertThat(result.success()).isTrue();
        List<Finding> findings = findingRepository.findAllByPullRequestId(pr.getId());
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.getAntiPattern()).isEqualTo("SECURITY_HARDCODED_SECRET");
            assertThat(f.getCategory()).isEqualTo("SECURITY");
            assertThat(f.getSeverity()).isEqualTo("critical");
        });
        assertThat(jdbc.queryForList(
                "SELECT status FROM ml.prediction_events WHERE pull_request_id = ? ORDER BY reported_line_start",
                String.class, pr.getId()))
                .containsExactly("persisted", "rejected_unknown_taxonomy");
        assertThat(pullRequestRepository.findById(pr.getId()).orElseThrow().getStatus()).isEqualTo("reviewed");
    }

    @Test
    void constraintViolationFailsTheReviewWithoutRetry() {
        PullRequestEntity pr = savePullRequest();
        // prediction_events.engine is NOT NULL: a deterministic violation.
        when(mlWorkerService.review(anyString(), anyString())).thenReturn(response(null,
                new MlFinding("src/a.py", null, 2, 2, "SECURITY_HARDCODED_SECRET",
                        "SECURITY", "critical", new BigDecimal("0.9"), "hard-coded key")));

        ReviewResult result = reviewService.orchestrateReview(pr, diff(pr), run(pr));

        assertThat(result.success()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(pullRequestRepository.findById(pr.getId()).orElseThrow().getStatus()).isEqualTo("failed");
    }

    @Test
    void oversizedDiffFailsWithoutCallingTheWorker() {
        PullRequestEntity pr = savePullRequest();
        String huge = diff(pr) + "+" + "x".repeat(ReviewService.MAX_DIFF_CHARS) + "\n";

        ReviewResult result = reviewService.orchestrateReview(pr, huge, run(pr));

        assertThat(result.success()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.errorMessage()).startsWith("diff_too_large: Diff too large to review");
        PullRequestEntity reloaded = pullRequestRepository.findById(pr.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("failed");
        assertThat(reloaded.getErrorMessage()).startsWith("diff_too_large: Diff too large to review")
                .doesNotContain("xxxx");
        verify(mlWorkerService, never()).review(anyString(), anyString());
    }

    @Test
    void reReviewOfTheSameHeadRequeuesTheOutboxEventWithTheNewFindingIds() throws Exception {
        PullRequestEntity pr = savePullRequest();
        when(mlWorkerService.review(anyString(), anyString())).thenReturn(response("fallback",
                new MlFinding("src/a.py", null, 2, 2, "SECURITY_HARDCODED_SECRET",
                        "SECURITY", "critical", new BigDecimal("0.9"), "hard-coded key")));

        ReviewRun run = run(pr);
        assertThat(reviewService.orchestrateReview(pr, diff(pr), run).success()).isTrue();
        String key = "DATASET_CAPTURE_REQUESTED:" + pr.getId() + ":" + pr.getHeadSha();
        IngestionOutbox first = outboxRepository.findByDeduplicationKey(key).orElseThrow();
        // Simulate the first event having been consumed.
        jdbc.update("UPDATE ml.ingestion_outbox SET status = 'completed', attempt_count = 1 WHERE id = ?",
                first.getId());

        assertThat(reviewService.orchestrateReview(pr, diff(pr), run).success()).isTrue();

        IngestionOutbox second = outboxRepository.findByDeduplicationKey(key).orElseThrow();
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getStatus()).isEqualTo("pending");
        assertThat(second.getAttemptCount()).isZero();
        List<String> currentFindingIds = findingRepository.findAllByPullRequestId(pr.getId()).stream()
                .map(f -> f.getId().toString()).toList();
        assertThat(currentFindingIds).hasSize(1);
        assertThat(ids(second.getPayload(), "findingIds")).containsExactlyElementsOf(currentFindingIds);
        assertThat(ids(first.getPayload(), "findingIds")).doesNotContainAnyElementsOf(currentFindingIds);
        // Stored as a JSON object (not a JSON-encoded string), so SQL over the column works.
        String stored = jdbc.queryForObject(
                "SELECT CAST(payload AS VARCHAR) FROM ml.ingestion_outbox WHERE id = ?", String.class, second.getId());
        assertThat(objectMapper.readTree(stored).isObject()).isTrue();
        String metadata = jdbc.queryForObject(
                "SELECT CAST(raw_metadata AS VARCHAR) FROM ml.prediction_events WHERE pull_request_id = ? LIMIT 1",
                String.class, pr.getId());
        assertThat(objectMapper.readTree(metadata).isObject()).isTrue();
    }

    @Test
    void explainabilityFieldsArePersistedRedactedAndBounded() {
        PullRequestEntity pr = savePullRequest();
        String secretLine = "API_KEY = \"sk-live-abcdefghijklmnopqrstuvwxyz123456\"";
        when(mlWorkerService.review(anyString(), anyString())).thenReturn(response("fallback",
                new MlFinding("src/a.py", null, 2, 2, "SECURITY_HARDCODED_SECRET", "SECURITY", "critical",
                        new BigDecimal("0.9"), "hard-coded key", secretLine,
                        "r".repeat(400), "Test fixtures with fake keys are fine.")));

        assertThat(reviewService.orchestrateReview(pr, diff(pr), run(pr)).success()).isTrue();

        Finding stored = findingRepository.findAllByPullRequestId(pr.getId()).get(0);
        assertThat(stored.getEvidence()).contains("<REDACTED_SECRET>").doesNotContain("sk-live");
        assertThat(stored.getRemediation()).hasSize(ReviewService.MAX_REMEDIATION_CHARS);
        assertThat(stored.getCaveat()).isEqualTo("Test fixtures with fake keys are fine.");
    }

    @Test
    void reviewQueuesItsPublicationInTheSameTransaction() throws Exception {
        PullRequestEntity pr = savePullRequest();
        when(mlWorkerService.review(anyString(), anyString())).thenReturn(response("fallback",
                // Added line: an inline candidate.
                new MlFinding("src/a.py", null, 2, 2, "SECURITY_HARDCODED_SECRET",
                        "SECURITY", "critical", new BigDecimal("0.9"), "hard-coded key"),
                // Context line: shown in the summary, never as an inline comment.
                new MlFinding("src/a.py", null, 1, 1, "MAINTAINABILITY_PRINT_STATEMENT",
                        "MAINTAINABILITY", "minor", new BigDecimal("0.6"), "context")));
        ReviewRun run = new ReviewRun(claimDelivery(pr), 1, pr.getHeadSha(), true, true);

        ReviewResult result = reviewService.orchestrateReview(pr, diff(pr), run);

        ReviewPublication publication = publicationRepository.findById(result.publicationId()).orElseThrow();
        assertThat(publication.getStatus()).isEqualTo(ReviewPublication.PENDING);
        assertThat(publication.getHeadSha()).isEqualTo(pr.getHeadSha());
        assertThat(publication.getDeliveryId()).isEqualTo(run.deliveryId());
        assertThat(publication.isInlineComments()).isTrue();
        Finding added = findingRepository.findAllByPullRequestId(pr.getId()).stream()
                .filter(f -> f.getLineStart() == 2).findFirst().orElseThrow();
        assertThat(ids(publication.getInlineFindingIds())).containsExactly(added.getId().toString());
    }

    @Test
    void disabledCommentsQueueASkippedPublication() {
        PullRequestEntity pr = savePullRequest();
        when(mlWorkerService.review(anyString(), anyString())).thenReturn(response("fallback"));
        ReviewRun run = new ReviewRun(claimDelivery(pr), 1, pr.getHeadSha(), false, true);

        ReviewResult result = reviewService.orchestrateReview(pr, diff(pr), run);

        ReviewPublication publication = publicationRepository.findById(result.publicationId()).orElseThrow();
        assertThat(publication.getStatus()).isEqualTo(ReviewPublication.SKIPPED);
        assertThat(publication.isInlineComments()).isFalse();
    }

    /** Scenario: a newer push lands while the worker analyses the old head. */
    @Test
    void headThatMovedDuringAnalysisDiscardsTheStaleReview() {
        PullRequestEntity pr = savePullRequest();
        String newHead = "f".repeat(40);
        when(mlWorkerService.review(anyString(), anyString())).thenAnswer(inv -> {
            jdbc.update("UPDATE pull_requests SET head_sha = ?, status = 'processing' WHERE id = ?",
                    newHead, pr.getId());
            return response("fallback", new MlFinding("src/a.py", null, 2, 2, "SECURITY_HARDCODED_SECRET",
                    "SECURITY", "critical", new BigDecimal("0.9"), "hard-coded key"));
        });

        ReviewResult result = reviewService.orchestrateReview(pr, diff(pr), run(pr));

        assertThat(result.outcome()).isEqualTo(ReviewResult.Outcome.SUPERSEDED);
        PullRequestEntity reloaded = pullRequestRepository.findById(pr.getId()).orElseThrow();
        // The stale run neither reverted the head nor marked the PR reviewed.
        assertThat(reloaded.getHeadSha()).isEqualTo(newHead);
        assertThat(reloaded.getStatus()).isEqualTo("processing");
        assertThat(findingRepository.findAllByPullRequestId(pr.getId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_publications WHERE pr_id = ?",
                Integer.class, pr.getId())).isZero();
    }

    /** Scenario: the lease expired mid-analysis and another worker re-claimed the delivery. */
    @Test
    void staleAttemptCannotCommitItsReview() {
        PullRequestEntity pr = savePullRequest();
        ReviewRun stale = run(pr);
        // The lease expires and is recovered, then a second worker claims attempt 2.
        jdbc.update("UPDATE processed_webhooks SET processing_started_at = ? WHERE delivery_id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1200)), stale.deliveryId());
        assertThat(processedWebhookRepository.recoverStale(Instant.now().minusSeconds(600))).isGreaterThan(0);
        assertThat(processedWebhookRepository.claim(stale.deliveryId(), 1)).isEqualTo(1);
        when(mlWorkerService.review(anyString(), anyString())).thenReturn(response("fallback",
                new MlFinding("src/a.py", null, 2, 2, "SECURITY_HARDCODED_SECRET",
                        "SECURITY", "critical", new BigDecimal("0.9"), "hard-coded key")));

        ReviewResult result = reviewService.orchestrateReview(pr, diff(pr), stale);

        assertThat(result.outcome()).isEqualTo(ReviewResult.Outcome.LEASE_LOST);
        assertThat(findingRepository.findAllByPullRequestId(pr.getId())).isEmpty();
        assertThat(pullRequestRepository.findById(pr.getId()).orElseThrow().getStatus()).isEqualTo("processing");
        assertThat(publicationRepository.findByDeliveryId(stale.deliveryId())).isEmpty();
        // Nor can its failure path touch the PR.
        assertThat(reviewService.failPullRequest(pr.getId(), stale, "late failure")).isFalse();
    }

    @Test
    void repositorySettingsExcludeFindingsButKeepTheirPredictionEvents() {
        PullRequestEntity pr = savePullRequest();
        Repository repo = pr.getRepo();
        repo.setReviewEnabledRules(List.of("SECURITY_HARDCODED_SECRET")); // print rule disabled
        repositoryRepository.save(repo);
        MlFinding secret = new MlFinding("src/a.py", null, 2, 2, "SECURITY_HARDCODED_SECRET",
                "SECURITY", "critical", new BigDecimal("0.9"), "hard-coded key");
        MlFinding print = new MlFinding("src/a.py", null, 3, 3, "MAINTAINABILITY_PRINT_STATEMENT",
                "MAINTAINABILITY", "minor", new BigDecimal("0.5"), "print");
        when(mlWorkerService.review(anyString(), anyString())).thenReturn(response("fallback", secret, print));

        assertThat(reviewService.orchestrateReview(pr, diff(pr), run(pr)).success()).isTrue();

        assertThat(findingRepository.findAllByPullRequestId(pr.getId()))
                .extracting(Finding::getAntiPattern).containsExactly("SECURITY_HARDCODED_SECRET");
        assertThat(jdbc.queryForList(
                "SELECT status FROM ml.prediction_events WHERE pull_request_id = ? ORDER BY reported_line_start",
                String.class, pr.getId()))
                .containsExactly("persisted", "filtered_by_repo_settings");
        // The score covers only the findings the settings keep: 100 − 20 × 0.9.
        assertThat(pullRequestRepository.findById(pr.getId()).orElseThrow().getQualityScore())
                .isEqualByComparingTo("82.00");
    }

    @Test
    void triageCarriesOverToTheSameFindingOnTheNextPush() throws Exception {
        PullRequestEntity pr = savePullRequest();
        String secretLine = "API_KEY = \"<REDACTED_SECRET>\"";
        when(mlWorkerService.review(anyString(), anyString())).thenReturn(response("fallback",
                new MlFinding("src/a.py", null, 2, 2, "SECURITY_HARDCODED_SECRET", "SECURITY", "critical",
                        new BigDecimal("0.9"), "hard-coded key", secretLine, null, null),
                new MlFinding("src/a.py", null, 3, 3, "MAINTAINABILITY_PRINT_STATEMENT", "MAINTAINABILITY",
                        "minor", new BigDecimal("0.5"), "print", "print(\"old\")", null, null)));
        assertThat(reviewService.orchestrateReview(pr, diff(pr), run(pr)).success()).isTrue();
        jdbc.update("UPDATE findings SET status = 'dismissed' WHERE pr_id = ?", pr.getId());

        // Next push: the dismissed secret is unchanged (on a shifted line); the
        // print statement's code changed, so it is a new finding.
        String newHead = UUID.randomUUID().toString().replace("-", "") + "12345678";
        jdbc.update("UPDATE pull_requests SET head_sha = ? WHERE id = ?", newHead, pr.getId());
        pr.setHeadSha(newHead);
        when(mlWorkerService.review(anyString(), anyString())).thenReturn(response("fallback",
                new MlFinding("src/a.py", null, 3, 3, "SECURITY_HARDCODED_SECRET", "SECURITY", "critical",
                        new BigDecimal("0.9"), "hard-coded key", secretLine, null, null),
                new MlFinding("src/a.py", null, 2, 2, "MAINTAINABILITY_PRINT_STATEMENT", "MAINTAINABILITY",
                        "minor", new BigDecimal("0.5"), "print", "print(\"new\")", null, null)));
        ReviewRun next = new ReviewRun(claimDelivery(pr), 1, newHead, true, true);

        ReviewResult result = reviewService.orchestrateReview(pr, shiftedDiff(newHead), next);

        assertThat(result.success()).isTrue();
        List<Finding> findings = findingRepository.findAllByPullRequestId(pr.getId());
        assertThat(findings).extracting(Finding::getAntiPattern, Finding::getStatus).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("SECURITY_HARDCODED_SECRET", "dismissed"),
                org.assertj.core.groups.Tuple.tuple("MAINTAINABILITY_PRINT_STATEMENT", "open"));
        // A dismissed finding is never offered as an inline comment again.
        String printId = findings.stream().filter(f -> "open".equals(f.getStatus()))
                .findFirst().orElseThrow().getId().toString();
        assertThat(ids(publicationRepository.findById(result.publicationId()).orElseThrow().getInlineFindingIds()))
                .containsExactly(printId);
    }

    @Test
    void falsePositiveWithReasonSurvivesAReReviewOfUnchangedCode() {
        PullRequestEntity pr = savePullRequest();
        MlFinding secret = new MlFinding("src/a.py", null, 2, 2, "SECURITY_HARDCODED_SECRET", "SECURITY",
                "critical", new BigDecimal("0.9"), "hard-coded key", "API_KEY = \"<REDACTED_SECRET>\"", null, null);
        when(mlWorkerService.review(anyString(), anyString())).thenReturn(response("fallback", secret));
        assertThat(reviewService.orchestrateReview(pr, diff(pr), run(pr)).success()).isTrue();
        UUID reviewer = pr.getRepo().getOwner().getId();
        Instant triagedAt = Instant.parse("2026-01-02T03:04:05Z");
        jdbc.update("UPDATE findings SET status = 'false_positive', triage_reason = ?, triaged_at = ?, triaged_by = ?"
                        + " WHERE pr_id = ?",
                "test fixture key", java.sql.Timestamp.from(triagedAt), reviewer, pr.getId());

        // Next push: same flagged code, on a shifted line.
        String newHead = UUID.randomUUID().toString().replace("-", "") + "12345678";
        jdbc.update("UPDATE pull_requests SET head_sha = ? WHERE id = ?", newHead, pr.getId());
        pr.setHeadSha(newHead);
        when(mlWorkerService.review(anyString(), anyString())).thenReturn(response("fallback",
                new MlFinding("src/a.py", null, 3, 3, "SECURITY_HARDCODED_SECRET", "SECURITY", "critical",
                        new BigDecimal("0.9"), "hard-coded key", "API_KEY = \"<REDACTED_SECRET>\"", null, null)));
        ReviewRun next = new ReviewRun(claimDelivery(pr), 1, newHead, true, true);

        assertThat(reviewService.orchestrateReview(pr, shiftedDiff(newHead), next).success()).isTrue();

        assertThat(findingRepository.findAllByPullRequestId(pr.getId())).singleElement().satisfies(f -> {
            assertThat(f.getLineStart()).isEqualTo(3);
            assertThat(f.getStatus()).isEqualTo("false_positive");
            assertThat(f.getTriageReason()).isEqualTo("test fixture key");
            assertThat(f.getTriagedAt()).isEqualTo(triagedAt);
            assertThat(f.getTriagedBy()).isEqualTo(reviewer);
        });
    }

    /** Both lines added, in the opposite order from {@link #diff}. */
    private static String shiftedDiff(String head) {
        return """
                diff --git a/src/a.py b/src/a.py
                --- a/src/a.py
                +++ b/src/a.py
                @@ -1,1 +1,3 @@
                 import os
                +print("%s")
                +API_KEY = "sk-live-abcdefghijklmnopqrstuvwxyz123456"
                """.formatted(head.substring(0, 12));
    }

    private List<String> ids(String payload, String field) throws Exception {
        return ids(objectMapper.readTree(payload).get(field).toString());
    }

    private List<String> ids(String jsonArray) throws Exception {
        List<String> out = new ArrayList<>();
        for (JsonNode node : objectMapper.readTree(jsonArray)) {
            out.add(node.asText());
        }
        return out;
    }

    /** A received delivery for the PR's head, claimed as attempt 1. */
    private String claimDelivery(PullRequestEntity pr) {
        String deliveryId = "scenario-" + UUID.randomUUID();
        assertThat(processedWebhookRepository.insertReceived(deliveryId, pr.getRepo().getId(),
                pr.getGithubPrNumber(), pr.getHeadSha(), "opened")).isEqualTo(1);
        assertThat(processedWebhookRepository.claim(deliveryId, 0)).isEqualTo(1);
        return deliveryId;
    }

    private ReviewRun run(PullRequestEntity pr) {
        return new ReviewRun(claimDelivery(pr), 1, pr.getHeadSha(), true, false);
    }

    private static MlReviewResponse response(String engine, MlFinding... findings) {
        return new MlReviewResponse(List.of(findings), new BigDecimal("80.00"), 5, 1,
                engine, "rules-v1", "1.0.0");
    }

    private PullRequestEntity savePullRequest() {
        long githubId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
        User owner = userRepository.save(User.builder()
                .githubId(githubId)
                .githubUsername("review-owner-" + githubId)
                .accessToken(encryptionService.encrypt("gh-token"))
                .build());
        Repository repo = repositoryRepository.save(Repository.builder()
                .githubId(githubId)
                .fullName("owner/review-" + githubId)
                .owner(owner)
                .webhookId(1L)
                .webhookSecret(encryptionService.encrypt("secret"))
                .build());
        return pullRequestRepository.save(PullRequestEntity.builder()
                .repo(repo)
                .githubPrNumber(1)
                .headSha(UUID.randomUUID().toString().replace("-", "") + "abcdefgh")
                .status("processing")
                .build());
    }
}
