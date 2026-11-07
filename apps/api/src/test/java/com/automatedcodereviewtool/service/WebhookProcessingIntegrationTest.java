package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.dto.MlFinding;
import com.automatedcodereviewtool.entity.ProcessedWebhook;
import com.automatedcodereviewtool.exception.InvalidDiffException;
import com.automatedcodereviewtool.dto.MlReviewResponse;
import com.automatedcodereviewtool.entity.Finding;
import com.automatedcodereviewtool.entity.PullRequestEntity;
import com.automatedcodereviewtool.entity.Repository;
import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.repository.FindingRepository;
import com.automatedcodereviewtool.repository.ProcessedWebhookRepository;
import com.automatedcodereviewtool.repository.PullRequestRepository;
import com.automatedcodereviewtool.repository.RepositoryRepository;
import com.automatedcodereviewtool.repository.UserRepository;
import com.automatedcodereviewtool.security.EncryptionService;
import com.automatedcodereviewtool.webhook.GitHubWebhookEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Webhook delivery → review → PR comment, against H2 with the real
 * services and repositories. Only GitHub and the ML worker are mocked.
 * Background pollers are slowed down so they don't race the test.
 */
@SpringBootTest(properties = {
        "app.webhook.recovery-delay-ms=3600000",
        "app.outbox.poll-delay-ms=3600000"
})
@ActiveProfiles("test")
class WebhookProcessingIntegrationTest {

    private static final AtomicLong GITHUB_IDS = new AtomicLong(990_000L);

    private static final String DIFF = """
            diff --git a/src/a.py b/src/a.py
            --- a/src/a.py
            +++ b/src/a.py
            @@ -1,1 +1,2 @@
             import os
            +API_KEY = "sk-live-abcdefghijklmnopqrstuvwxyz123456"
            diff --git a/src/b.py b/src/b.py
            --- a/src/b.py
            +++ b/src/b.py
            @@ -1,1 +1,2 @@
             import sys
            +print("hi")
            """;

    @Autowired private WebhookService webhookService;
    @Autowired private UserRepository userRepository;
    @Autowired private RepositoryRepository repositoryRepository;
    @Autowired private PullRequestRepository pullRequestRepository;
    @Autowired private FindingRepository findingRepository;
    @Autowired private ProcessedWebhookRepository processedWebhookRepository;
    @Autowired private EncryptionService encryptionService;

    @MockBean private GitHubService gitHubService;
    @MockBean private MlWorkerService mlWorkerService;

    @Test
    void deliveryIsReviewedAndCommented() {
        long githubId = GITHUB_IDS.incrementAndGet();
        Repository repo = saveRepo(githubId, true);
        String headSha = "a".repeat(40);

        when(gitHubService.getFileDiff("gh-token", repo.getFullName(), 7)).thenReturn(DIFF);
        when(mlWorkerService.review(anyString(), anyString())).thenReturn(new MlReviewResponse(
                List.of(
                        // Line 2 exists in both files; filePath decides.
                        new MlFinding("src/b.py", null, 2, 2, "MAINTAINABILITY_PRINT_STATEMENT",
                                "MAINTAINABILITY", "minor", new BigDecimal("0.7"), "print left in code"),
                        new MlFinding("src/a.py", null, 2, 2, "SECURITY_HARDCODED_SECRET",
                                "SECURITY", "critical", new BigDecimal("0.95"), "hard-coded key")),
                new BigDecimal("78.90"), 5, 2, "fallback", "rules-v1", "1.0.0"));

        assertThat(webhookService.receiveDelivery("delivery-ok-" + githubId,
                event(githubId, headSha), githubId)).isTrue();
        webhookService.processDelivery("delivery-ok-" + githubId);

        PullRequestEntity pr = pullRequestRepository.findByRepoAndGithubPrNumber(repo, 7).orElseThrow();
        assertThat(pr.getStatus()).isEqualTo("reviewed");
        assertThat(pr.getQualityScore()).isEqualByComparingTo("78.90");

        List<Finding> findings = findingRepository.findAllByPullRequestId(pr.getId());
        assertThat(findings)
                .extracting(Finding::getFilePath, Finding::getAntiPattern)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("src/b.py", "MAINTAINABILITY_PRINT_STATEMENT"),
                        org.assertj.core.groups.Tuple.tuple("src/a.py", "SECURITY_HARDCODED_SECRET"));

        assertThat(repositoryRepository.findById(repo.getId()).orElseThrow().getQualityScore())
                .isEqualByComparingTo("78.90");

        ArgumentCaptor<String> comment = ArgumentCaptor.forClass(String.class);
        verify(gitHubService).postPrComment(eq("gh-token"), eq(repo.getFullName()), eq(7), comment.capture());
        assertThat(comment.getValue()).contains("detector: rule-based").contains("SECURITY_HARDCODED_SECRET");

        assertThat(processedWebhookRepository.findById("delivery-ok-" + githubId).orElseThrow().getStatus())
                .isEqualTo("completed");
    }

    @Test
    void workerRejectionDeadLettersDeliveryWithoutRetry() {
        long githubId = GITHUB_IDS.incrementAndGet();
        Repository repo = saveRepo(githubId, true);
        String deliveryId = "delivery-422-" + githubId;

        when(gitHubService.getFileDiff("gh-token", repo.getFullName(), 7)).thenReturn(DIFF);
        when(mlWorkerService.review(anyString(), anyString()))
                .thenThrow(new InvalidDiffException(422, "ML worker rejected diff: too large"));

        assertThat(webhookService.receiveDelivery(deliveryId, event(githubId, "c".repeat(40)), githubId)).isTrue();
        webhookService.processDelivery(deliveryId);

        ProcessedWebhook delivery = processedWebhookRepository.findById(deliveryId).orElseThrow();
        assertThat(delivery.getStatus()).isEqualTo("dead_letter");
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getLastError()).contains("too large");
        assertThat(pullRequestRepository.findByRepoAndGithubPrNumber(repo, 7).orElseThrow().getStatus())
                .isEqualTo("failed");

        // Not picked up again by the recovery job.
        assertThat(processedWebhookRepository.findEligibleDeliveryIds(100)).doesNotContain(deliveryId);
    }

    @Test
    void deliveryForDisconnectedRepoIsIgnored() {
        long githubId = GITHUB_IDS.incrementAndGet();
        saveRepo(githubId, false);

        assertThat(webhookService.receiveDelivery("delivery-off-" + githubId,
                event(githubId, "b".repeat(40)), githubId)).isFalse();
        assertThat(processedWebhookRepository.findById("delivery-off-" + githubId)).isEmpty();
    }

    private Repository saveRepo(long githubId, boolean active) {
        User owner = userRepository.save(User.builder()
                .githubId(githubId)
                .githubUsername("owner-" + githubId)
                .accessToken(encryptionService.encrypt("gh-token"))
                .build());
        return repositoryRepository.save(Repository.builder()
                .githubId(githubId)
                .fullName("owner/repo-" + githubId)
                .owner(owner)
                .webhookId(1L)
                .webhookSecret(encryptionService.encrypt("secret"))
                .isActive(active)
                .build());
    }

    private static GitHubWebhookEvent event(long githubId, String headSha) {
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
