package com.automatedcodereviewtool.controller;

import com.automatedcodereviewtool.entity.Repository;
import com.automatedcodereviewtool.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET/PUT /api/repos/{id}/settings} against H2 with the V20 constraints. */
class RepoSettingsTest extends OwnedApiTestSupport {

    static final String VALID_SETTINGS = """
            {"enabledRules":["SECURITY_WEAK_CRYPTO","SECURITY_HARDCODED_SECRET","SECURITY_WEAK_CRYPTO"],
             "minSeverity":"major","minConfidence":0.6,"postComments":false,"inlineComments":true,
             "languages":["java","python","java"]}
            """;

    private User owner;
    private Repository repo;

    @BeforeEach
    void seed() {
        owner = newUser("settings");
        repo = newRepo(owner, owner.getGithubUsername() + "/app");
    }

    private ResultActions putSettings(String json) throws Exception {
        return mockMvc.perform(as(owner, put("/api/repos/{id}/settings", repo.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)));
    }

    @Test
    void get_returnsDefaultsForANewRepository() throws Exception {
        mockMvc.perform(as(owner, get("/api/repos/{id}/settings", repo.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabledRules.length()").value(0))
                .andExpect(jsonPath("$.minSeverity").value("minor"))
                .andExpect(jsonPath("$.minConfidence").value(0.0))
                .andExpect(jsonPath("$.postComments").value(true))
                .andExpect(jsonPath("$.inlineComments").value(false))
                .andExpect(jsonPath("$.languages").value(org.hamcrest.Matchers.contains(
                        "python", "javascript", "typescript", "java")));
    }

    @Test
    void put_persistsNormalizedSettings() throws Exception {
        putSettings(VALID_SETTINGS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabledRules").value(org.hamcrest.Matchers.contains(
                        "SECURITY_HARDCODED_SECRET", "SECURITY_WEAK_CRYPTO")))
                .andExpect(jsonPath("$.minSeverity").value("major"))
                .andExpect(jsonPath("$.minConfidence").value(0.6))
                .andExpect(jsonPath("$.postComments").value(false))
                .andExpect(jsonPath("$.inlineComments").value(true))
                .andExpect(jsonPath("$.languages").value(org.hamcrest.Matchers.contains("python", "java")));

        Repository stored = repositoryRepository.findById(repo.getId()).orElseThrow();
        assertThat(stored.getReviewEnabledRules()).containsExactly("SECURITY_HARDCODED_SECRET", "SECURITY_WEAK_CRYPTO");
        assertThat(stored.getReviewMinSeverity()).isEqualTo("major");
        assertThat(stored.getReviewMinConfidence()).isEqualByComparingTo(new BigDecimal("0.6"));
        assertThat(stored.isReviewPostComments()).isFalse();
        assertThat(stored.isReviewInlineComments()).isTrue();
        assertThat(stored.getReviewLanguages()).containsExactly("python", "java");
        assertThat(jdbc.queryForObject("SELECT review_enabled_rules FROM repositories WHERE id = ?",
                String.class, repo.getId())).isEqualTo("SECURITY_HARDCODED_SECRET,SECURITY_WEAK_CRYPTO");

        mockMvc.perform(as(owner, get("/api/repos/{id}/settings", repo.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minSeverity").value("major"))
                .andExpect(jsonPath("$.languages.length()").value(2));
    }

    @Test
    void put_emptyRuleListMeansAllRules() throws Exception {
        putSettings(VALID_SETTINGS.replace(
                "[\"SECURITY_WEAK_CRYPTO\",\"SECURITY_HARDCODED_SECRET\",\"SECURITY_WEAK_CRYPTO\"]", "[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabledRules.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT review_enabled_rules FROM repositories WHERE id = ?",
                String.class, repo.getId())).isEmpty();
    }

    @Test
    void put_rejectsUnknownRuleId() throws Exception {
        putSettings(VALID_SETTINGS.replace("SECURITY_HARDCODED_SECRET", "SECURITY_NOT_A_RULE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("SECURITY_NOT_A_RULE")));
        assertDefaultsUnchanged();
    }

    @Test
    void put_rejectsRuleIdWithWrongCase() throws Exception {
        putSettings(VALID_SETTINGS.replace("SECURITY_HARDCODED_SECRET", "security_hardcoded_secret"))
                .andExpect(status().isBadRequest());
        assertDefaultsUnchanged();
    }

    @Test
    void put_rejectsUnsupportedLanguage() throws Exception {
        putSettings(VALID_SETTINGS.replace("\"python\"", "\"rust\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("rust")));
        assertDefaultsUnchanged();
    }

    @Test
    void put_rejectsInvalidScalarsAndMissingFields() throws Exception {
        for (String body : List.of(
                VALID_SETTINGS.replace("0.6", "1.5"),
                VALID_SETTINGS.replace("0.6", "-0.1"),
                VALID_SETTINGS.replace("\"major\"", "\"blocker\""),
                VALID_SETTINGS.replace("[\"java\",\"python\",\"java\"]", "[]"),
                VALID_SETTINGS.replace("\"postComments\":false,", ""),
                VALID_SETTINGS.replace("\"minConfidence\":0.6", "\"minConfidence\":null"),
                "{}")) {
            putSettings(body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        }
        putSettings("not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
        assertDefaultsUnchanged();
    }

    @Test
    void databaseConstraintsRejectValuesTheApiWouldNeverWrite() {
        for (String sql : List.of(
                "UPDATE repositories SET review_min_severity = 'blocker' WHERE id = ?",
                "UPDATE repositories SET review_min_confidence = 1.5 WHERE id = ?",
                "UPDATE repositories SET review_languages = 'python,rust' WHERE id = ?",
                "UPDATE repositories SET review_languages = '' WHERE id = ?",
                "UPDATE repositories SET review_enabled_rules = 'A,,B' WHERE id = ?")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(sql, repo.getId()))
                    .as(sql)
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }

    @Test
    void reconnectByAnotherAccountResetsSettingsToDefaults() throws Exception {
        putSettings(VALID_SETTINGS).andExpect(status().isOk());
        Repository stored = repositoryRepository.findById(repo.getId()).orElseThrow();
        stored.setActive(false);
        repositoryRepository.save(stored);

        User next = newUser("next");
        org.mockito.Mockito.when(gitHubService.getRepository(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Map.of("id", stored.getGithubId(), "private", false));
        org.mockito.Mockito.when(gitHubService.installWebhook(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(42L);

        mockMvc.perform(as(next, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/repos/connect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"" + stored.getFullName() + "\"}")))
                .andExpect(status().isCreated());

        mockMvc.perform(as(next, get("/api/repos/{id}/settings", repo.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabledRules.length()").value(0))
                .andExpect(jsonPath("$.minSeverity").value("minor"))
                .andExpect(jsonPath("$.postComments").value(true))
                .andExpect(jsonPath("$.languages.length()").value(4));
    }

    private void assertDefaultsUnchanged() {
        Repository stored = repositoryRepository.findById(repo.getId()).orElseThrow();
        assertThat(stored.getReviewEnabledRules()).isEmpty();
        assertThat(stored.getReviewMinSeverity()).isEqualTo("minor");
        assertThat(stored.getReviewLanguages()).containsExactlyElementsOf(Repository.REVIEW_LANGUAGES);
    }
}
