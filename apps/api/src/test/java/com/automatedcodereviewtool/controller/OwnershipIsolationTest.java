package com.automatedcodereviewtool.controller;

import com.automatedcodereviewtool.entity.Finding;
import com.automatedcodereviewtool.entity.PullRequestEntity;
import com.automatedcodereviewtool.entity.Repository;
import com.automatedcodereviewtool.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-user isolation on H2 with the real persistence layer: Alice and Bob
 * each own a repository with the same short name ({@code alice/app},
 * {@code bob/app}). Every owner-scoped route is called by Alice with Bob's
 * ids and must answer 404 without touching Bob's data.
 */
class OwnershipIsolationTest extends OwnedApiTestSupport {

    private User alice;
    private User bob;
    private Repository aliceRepo;
    private Repository bobRepo;
    private PullRequestEntity bobPr;
    private Finding aliceFinding;
    private Finding bobFinding;

    @BeforeEach
    void seed() {
        alice = newUser("alice");
        bob = newUser("bob");
        aliceRepo = newRepo(alice, "alice/app");
        bobRepo = newRepo(bob, "bob/app");
        PullRequestEntity alicePr = newPr(aliceRepo, 1, "reviewed");
        bobPr = newPr(bobRepo, 1, "reviewed");
        aliceFinding = newFinding(alicePr, "SECURITY_WEAK_CRYPTO", "open", "fallback");
        bobFinding = newFinding(bobPr, "SECURITY_WEAK_CRYPTO", "open", "fallback");
    }

    private MockHttpServletRequestBuilder asAlice(MockHttpServletRequestBuilder builder) {
        return as(alice, builder);
    }

    // --- repositories ---------------------------------------------------------

    @Test
    void list_returnsOnlyCallersRepositories() throws Exception {
        mockMvc.perform(asAlice(get("/api/repos")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fullName").value("alice/app"))
                .andExpect(jsonPath("$[0].id").value(aliceRepo.getId().toString()));
    }

    @Test
    void everyRepoRoute_returns404ForAnotherUsersRepo() throws Exception {
        String id = bobRepo.getId().toString();
        List<MockHttpServletRequestBuilder> requests = List.of(
                get("/api/repos/{id}", id),
                get("/api/repos/{id}/prs", id),
                get("/api/repos/{id}/settings", id),
                put("/api/repos/{id}/settings", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RepoSettingsTest.VALID_SETTINGS),
                get("/api/metrics/quality-trend").param("repo", id),
                get("/api/metrics/finding-summary").param("repo", id),
                delete("/api/repos/{id}", id));
        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(asAlice(request))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        }

        Repository reloaded = repositoryRepository.findById(bobRepo.getId()).orElseThrow();
        assertThat(reloaded.isActive()).isTrue();
        assertThat(reloaded.getReviewMinSeverity()).isEqualTo("minor");
        assertThat(reloaded.isReviewPostComments()).isTrue();
        verify(gitHubService, never()).deleteWebhook(anyString(), anyString(), anyLong());
    }

    @Test
    void ownRepoRoutes_succeed() throws Exception {
        String id = aliceRepo.getId().toString();
        for (MockHttpServletRequestBuilder request : List.of(
                get("/api/repos/{id}", id),
                get("/api/repos/{id}/prs", id),
                get("/api/repos/{id}/settings", id),
                get("/api/metrics/quality-trend").param("repo", id),
                get("/api/metrics/finding-summary").param("repo", id))) {
            mockMvc.perform(asAlice(request)).andExpect(status().isOk());
        }
    }

    // --- reviews and triage -----------------------------------------------------

    @Test
    void review_returns404ForAnotherUsersPullRequest() throws Exception {
        mockMvc.perform(asAlice(get("/api/reviews/{prId}", bobPr.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void triage_returns404ForAnotherUsersFinding_andLeavesItUntouched() throws Exception {
        for (String action : List.of("accept", "fix", "reopen")) {
            mockMvc.perform(asAlice(post("/api/scan/action")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"findingId\":\"%s\",\"action\":\"%s\"}".formatted(bobFinding.getId(), action))))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(asAlice(post("/api/scan/action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"findingId\":\"%s\",\"action\":\"false_positive\",\"reason\":\"not mine\"}"
                                .formatted(bobFinding.getId()))))
                .andExpect(status().isNotFound());

        Finding reloaded = findingRepository.findById(bobFinding.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("open");
        assertThat(reloaded.getTriagedAt()).isNull();
        assertThat(reloaded.getTriagedBy()).isNull();
    }

    @Test
    void triage_persistsAuditFieldsAndAppearsInReview() throws Exception {
        Instant before = Instant.now().minusSeconds(1);
        mockMvc.perform(asAlice(post("/api/scan/action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"findingId\":\"%s\",\"action\":\"false_positive\",\"reason\":\"  hashing a cache key  \"}"
                                .formatted(aliceFinding.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("false_positive"))
                .andExpect(jsonPath("$.reason").value("hashing a cache key"))
                .andExpect(jsonPath("$.triagedBy").value(alice.getGithubUsername()));

        Finding stored = findingRepository.findById(aliceFinding.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo("false_positive");
        assertThat(stored.getTriageReason()).isEqualTo("hashing a cache key");
        assertThat(stored.getTriagedBy()).isEqualTo(alice.getId());
        assertThat(stored.getTriagedAt()).isAfter(before);

        mockMvc.perform(asAlice(get("/api/reviews/{prId}", stored.getPullRequest().getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.findings[0].status").value("false_positive"))
                .andExpect(jsonPath("$.findings[0].triageReason").value("hashing a cache key"))
                .andExpect(jsonPath("$.findings[0].triagedAt").isNotEmpty());

        mockMvc.perform(asAlice(post("/api/scan/action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"findingId\":\"%s\",\"action\":\"reopen\"}".formatted(aliceFinding.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("open"));
        Finding reopened = findingRepository.findById(aliceFinding.getId()).orElseThrow();
        assertThat(reopened.getStatus()).isEqualTo("open");
        assertThat(reopened.getTriageReason()).isNull();
    }

    @Test
    void findingStatusConstraints_rejectUnknownStatusAndUnexplainedFalsePositive() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "UPDATE findings SET status = 'wontfix' WHERE id = ?", aliceFinding.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "UPDATE findings SET status = 'false_positive', triage_reason = NULL WHERE id = ?",
                        aliceFinding.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // A dismissal recorded before V19 has no reason and must stay valid.
        jdbc.update("UPDATE findings SET status = 'dismissed', triage_reason = NULL WHERE id = ?",
                aliceFinding.getId());
    }

    // --- error shape -------------------------------------------------------------

    @Test
    void malformedIdsAndParameters_return400Json_not500() throws Exception {
        mockMvc.perform(asAlice(get("/api/repos/not-a-uuid")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Parameter 'id' is invalid"));
        mockMvc.perform(asAlice(get("/api/metrics/finding-summary").param("repo", "x")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        mockMvc.perform(asAlice(get("/api/metrics/quality-trend")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Parameter 'repo' is required"));
        mockMvc.perform(asAlice(get("/api/metrics/finding-summary")
                        .param("repo", aliceRepo.getId().toString()).param("days", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        mockMvc.perform(asAlice(get("/api/reviews/{prId}", "123")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownRouteAndWrongMethod_returnClientErrors_not500() throws Exception {
        mockMvc.perform(asAlice(get("/api/does-not-exist")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        mockMvc.perform(asAlice(patch("/api/repos")))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"));
        mockMvc.perform(asAlice(post("/api/scan/action")
                        .contentType(MediaType.TEXT_PLAIN).content("x")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    // --- dashboard aggregates --------------------------------------------------------

    @Test
    void repoSummary_lastReviewedAtIgnoresUnreviewedPullRequests() throws Exception {
        PullRequestEntity reviewed = newPr(aliceRepo, 2, "reviewed");
        reviewed.setReviewedAt(Instant.parse("2026-10-01T12:00:00Z"));
        pullRequestRepository.save(reviewed);
        // Still processing: reviewed_at is NULL, which sorts first under DESC on Postgres.
        newPr(aliceRepo, 3, "processing");

        mockMvc.perform(asAlice(get("/api/repos/{id}", aliceRepo.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPrsReviewed").value(2))
                .andExpect(jsonPath("$.lastReviewedAt").value("2026-10-01T12:00:00Z"));
    }

    @Test
    void findingSummary_countsTriageOutcomesPerRuleAndEngine() throws Exception {
        PullRequestEntity pr = newPr(aliceRepo, 4, "reviewed");
        newFinding(pr, "SECURITY_WEAK_CRYPTO", "false_positive", "fallback");
        newFinding(pr, "SECURITY_WEAK_CRYPTO", "dismissed", "model");
        newFinding(pr, "SECURITY_WEAK_CRYPTO", "accepted", null);
        newFinding(pr, "READABILITY_MAGIC_NUMBER", "fixed", "fallback");
        // Bob's findings must not leak into Alice's summary.
        newFinding(bobPr, "READABILITY_MAGIC_NUMBER", "open", "fallback");

        mockMvc.perform(asAlice(get("/api/metrics/finding-summary")
                        .param("repo", aliceRepo.getId().toString()).param("days", "7")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repoId").value(aliceRepo.getId().toString()))
                .andExpect(jsonPath("$.days").value(7))
                .andExpect(jsonPath("$.byRule.length()").value(2))
                .andExpect(jsonPath("$.byRule[0].antiPattern").value("SECURITY_WEAK_CRYPTO"))
                .andExpect(jsonPath("$.byRule[0].total").value(4))
                .andExpect(jsonPath("$.byRule[0].open").value(1))
                .andExpect(jsonPath("$.byRule[0].accepted").value(1))
                .andExpect(jsonPath("$.byRule[0].dismissed").value(1))
                .andExpect(jsonPath("$.byRule[0].fixed").value(0))
                .andExpect(jsonPath("$.byRule[0].falsePositive").value(1))
                .andExpect(jsonPath("$.byRule[1].antiPattern").value("READABILITY_MAGIC_NUMBER"))
                .andExpect(jsonPath("$.byRule[1].total").value(1))
                .andExpect(jsonPath("$.byRule[1].fixed").value(1))
                .andExpect(jsonPath("$.byEngine[0].engine").value("fallback"))
                .andExpect(jsonPath("$.byEngine[0].total").value(3))
                .andExpect(jsonPath("$.byEngine[?(@.engine == 'model')].total").value(1))
                .andExpect(jsonPath("$.byEngine[?(@.engine == 'unknown')].total").value(1));
    }

    @Test
    void findingSummary_excludesFindingsOutsideTheWindow() throws Exception {
        jdbc.update("UPDATE findings SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(java.time.Duration.ofDays(40))), aliceFinding.getId());

        mockMvc.perform(asAlice(get("/api/metrics/finding-summary")
                        .param("repo", aliceRepo.getId().toString()).param("days", "30")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byRule.length()").value(0))
                .andExpect(jsonPath("$.byEngine.length()").value(0));
    }

    @Test
    void settingsConfidenceIsStoredWithDatabasePrecision() throws Exception {
        mockMvc.perform(asAlice(put("/api/repos/{id}/settings", aliceRepo.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RepoSettingsTest.VALID_SETTINGS.replace("0.6", "0.66666"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minConfidence").value(0.667));
        assertThat(repositoryRepository.findById(aliceRepo.getId()).orElseThrow().getReviewMinConfidence())
                .isEqualByComparingTo(new BigDecimal("0.667"));
        verify(gitHubService, never()).installWebhook(any(), any(), any(), any());
    }
}
