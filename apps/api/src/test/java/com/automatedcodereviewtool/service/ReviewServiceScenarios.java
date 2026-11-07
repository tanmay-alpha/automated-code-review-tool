package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.dto.MlFinding;
import com.automatedcodereviewtool.dto.MlReviewResponse;
import com.automatedcodereviewtool.dto.ReviewResult;
import com.automatedcodereviewtool.entity.Finding;
import com.automatedcodereviewtool.entity.IngestionOutbox;
import com.automatedcodereviewtool.entity.PullRequestEntity;
import com.automatedcodereviewtool.entity.Repository;
import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.repository.FindingRepository;
import com.automatedcodereviewtool.repository.IngestionOutboxRepository;
import com.automatedcodereviewtool.repository.PullRequestRepository;
import com.automatedcodereviewtool.repository.RepositoryRepository;
import com.automatedcodereviewtool.repository.UserRepository;
import com.automatedcodereviewtool.security.EncryptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
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
 * {@link ReviewService#orchestrateReview} against a real database with only
 * the ML worker mocked. Run on H2 ({@link ReviewServiceIntegrationTest}) and
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

        ReviewResult result = reviewService.orchestrateReview(pr, diff(pr));

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

        ReviewResult result = reviewService.orchestrateReview(pr, diff(pr));

        assertThat(result.success()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(pullRequestRepository.findById(pr.getId()).orElseThrow().getStatus()).isEqualTo("failed");
    }

    @Test
    void oversizedDiffFailsWithoutCallingTheWorker() {
        PullRequestEntity pr = savePullRequest();
        String huge = diff(pr) + "+" + "x".repeat(ReviewService.MAX_DIFF_CHARS) + "\n";

        ReviewResult result = reviewService.orchestrateReview(pr, huge);

        assertThat(result.success()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.errorMessage()).startsWith("Diff too large to review");
        PullRequestEntity reloaded = pullRequestRepository.findById(pr.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("failed");
        assertThat(reloaded.getErrorMessage()).startsWith("Diff too large to review").doesNotContain("xxxx");
        verify(mlWorkerService, never()).review(anyString(), anyString());
    }

    @Test
    void reReviewOfTheSameHeadRequeuesTheOutboxEventWithTheNewFindingIds() throws Exception {
        PullRequestEntity pr = savePullRequest();
        when(mlWorkerService.review(anyString(), anyString())).thenReturn(response("fallback",
                new MlFinding("src/a.py", null, 2, 2, "SECURITY_HARDCODED_SECRET",
                        "SECURITY", "critical", new BigDecimal("0.9"), "hard-coded key")));

        assertThat(reviewService.orchestrateReview(pr, diff(pr)).success()).isTrue();
        String key = "DATASET_CAPTURE_REQUESTED:" + pr.getId() + ":" + pr.getHeadSha();
        IngestionOutbox first = outboxRepository.findByDeduplicationKey(key).orElseThrow();
        // Simulate the first event having been consumed.
        jdbc.update("UPDATE ml.ingestion_outbox SET status = 'completed', attempt_count = 1 WHERE id = ?",
                first.getId());

        assertThat(reviewService.orchestrateReview(pr, diff(pr)).success()).isTrue();

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

    private List<String> ids(String payload, String field) throws Exception {
        List<String> out = new ArrayList<>();
        for (JsonNode node : objectMapper.readTree(payload).get(field)) {
            out.add(node.asText());
        }
        return out;
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
