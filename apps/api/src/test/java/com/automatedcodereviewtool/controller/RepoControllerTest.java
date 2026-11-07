package com.automatedcodereviewtool.controller;

import com.automatedcodereviewtool.entity.PullRequestEntity;
import com.automatedcodereviewtool.entity.Repository;
import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.repository.PullRequestRepository;
import com.automatedcodereviewtool.repository.RepositoryRepository;
import com.automatedcodereviewtool.repository.UserRepository;
import com.automatedcodereviewtool.security.ApiKeyAuthFilter;
import com.automatedcodereviewtool.security.AuthRateLimitFilter;
import com.automatedcodereviewtool.security.JwtAuthFilter;
import com.automatedcodereviewtool.security.EncryptionService;
import com.automatedcodereviewtool.service.ApiKeyService;
import com.automatedcodereviewtool.service.GitHubService;
import com.automatedcodereviewtool.service.RepoService;
import jakarta.servlet.FilterChain;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for {@link RepoController} using {@code @SpringBootTest}
 * with an H2 in-memory database (PostgreSQL-compatibility mode) and the
 * real Flyway migrations.
 *
 * <p>The {@link com.automatedcodereviewtool.security.ApiKeyAuthFilter} is replaced with
 * a Mockito mock so we don't need Redis; the JWT auth is bypassed by
 * attaching an authenticated principal to each request via
 * {@link com.automatedcodereviewtool.security.test.SecurityMockMvcRequestPostProcessors#user}.
 * The {@code GitHubService} is mocked to avoid real HTTP calls.</p>
 *
 * <p>The api_keys table is exercised by the JPA layer; we don't need a
 * separate test for it because the V3 migration is auto-applied and
 * the entity reads it on every API-key request.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepoControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RepositoryRepository repositoryRepository;
    @Autowired PullRequestRepository pullRequestRepository;
    @Autowired com.automatedcodereviewtool.repository.QualityMetricRepository qualityMetricRepository;
    @Autowired com.automatedcodereviewtool.repository.FindingRepository findingRepository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired EncryptionService encryptionService;
    @Autowired RepoService repoService; // sanity: confirm wiring works

    @MockBean GitHubService gitHubService;
    @MockBean ApiKeyAuthFilter apiKeyAuthFilter;
    @MockBean ApiKeyService apiKeyService;
    // AuthRateLimitFilter also needs Redis (StringRedisTemplate) —
    // mock it as a pass-through so the security chain still runs
    // without needing an embedded Redis.
    @MockBean AuthRateLimitFilter authRateLimitFilter;
    // JwtAuthFilter is mocked as a pass-through: it never validates
    // a cookie, just delegates to the chain. With @MockBean + this
    // stubbed doFilter, the security chain still runs through Spring
    // Security's authorization rules (which see the principal attached
    // via with(user(...))) without ever inspecting a real cookie.
    @MockBean JwtAuthFilter jwtAuthFilter;

    @org.junit.jupiter.api.BeforeEach
    void stubJwtAuthFilter() throws Exception {
        // Stub both filter mocks as pass-throughs so the request
        // reaches the controller. The real JWT principal is provided
        // per-request via with(user(testUser)) below.
        org.mockito.Mockito.doAnswer(inv -> {
            jakarta.servlet.ServletRequest req = inv.getArgument(0);
            jakarta.servlet.ServletResponse res = inv.getArgument(1);
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(req, res);
            return null;
        }).when(jwtAuthFilter).doFilter(
                org.mockito.ArgumentMatchers.any(jakarta.servlet.ServletRequest.class),
                org.mockito.ArgumentMatchers.any(jakarta.servlet.ServletResponse.class),
                org.mockito.ArgumentMatchers.any(FilterChain.class));

        org.mockito.Mockito.doAnswer(inv -> {
            jakarta.servlet.ServletRequest req = inv.getArgument(0);
            jakarta.servlet.ServletResponse res = inv.getArgument(1);
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(req, res);
            return null;
        }).when(apiKeyAuthFilter).doFilter(
                org.mockito.ArgumentMatchers.any(jakarta.servlet.ServletRequest.class),
                org.mockito.ArgumentMatchers.any(jakarta.servlet.ServletResponse.class),
                org.mockito.ArgumentMatchers.any(FilterChain.class));

        org.mockito.Mockito.doAnswer(inv -> {
            jakarta.servlet.ServletRequest req = inv.getArgument(0);
            jakarta.servlet.ServletResponse res = inv.getArgument(1);
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(req, res);
            return null;
        }).when(authRateLimitFilter).doFilter(
                org.mockito.ArgumentMatchers.any(jakarta.servlet.ServletRequest.class),
                org.mockito.ArgumentMatchers.any(jakarta.servlet.ServletResponse.class),
                org.mockito.ArgumentMatchers.any(FilterChain.class));
    }

    /** Plaintext GitHub token; the users row stores only its ciphertext. */
    private static final String GH_TOKEN = "gh_test_token";

    private User testUser;

    @BeforeEach
    void setUp() {
        // Wipe any repos left over from prior tests so each test
        // starts with a clean slate. Users are not deleted (we
        // want foreign keys to remain valid).
        repositoryRepository.deleteAll();

        testUser = userRepository.findByGithubUsername("alice").orElseGet(() -> User.builder()
                .githubUsername("alice")
                .githubId(12345L)
                .avatarUrl("https://a.c/a.png")
                .build());
        testUser.setAccessToken(encryptionService.encrypt(GH_TOKEN));
        testUser = userRepository.save(testUser);
    }

    // --- POST /api/repos/connect ------------------------------------------

    @Test
    void connect_returns201_andStoresRepo() throws Exception {
        Map<String, Object> repoJson = Map.of(
                "id", 999, "full_name", "alice/cool", "private", false,
                "description", "a cool repo");
        org.mockito.Mockito.when(gitHubService.getRepository(eq(GH_TOKEN), anyString(), anyString()))
                .thenReturn(repoJson);
        org.mockito.Mockito.when(gitHubService.installWebhook(eq(GH_TOKEN), anyString(), anyString(), anyString()))
                .thenReturn(777L);

        String body = """
                {"fullName":"alice/cool"}
                """;

        mockMvc.perform(connectRepo(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fullName").value("alice/cool"))
                .andExpect(jsonPath("$.githubId").value(999))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(header().exists("X-Webhook-Secret"));

        // Regression: GitHub must receive the decrypted token, never the at-rest ciphertext.
        org.mockito.Mockito.verify(gitHubService).getRepository(GH_TOKEN, "alice", "cool");
        org.mockito.Mockito.verify(gitHubService).installWebhook(
                eq(GH_TOKEN), eq("alice/cool"), anyString(), anyString());
    }

    @Test
    void connect_returns400_onInvalidOwnerRepoFormat() throws Exception {
        mockMvc.perform(connectRepo("""
                {"fullName":"no-slash"}
                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void connect_returns400_whenGitHubRepoMissing() throws Exception {
        org.mockito.Mockito.when(gitHubService.getRepository(eq(GH_TOKEN), anyString(), anyString()))
                .thenReturn(null);

        mockMvc.perform(connectRepo("""
                {"fullName":"alice/missing"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CONNECT_REPO_FAILED"));
    }

    @Test
    void connect_returns400_whenAlreadyConnected() throws Exception {
        Map<String, Object> repoJson = Map.of(
                "id", 12345L, "full_name", "alice/dup", "private", false);
        org.mockito.Mockito.when(gitHubService.getRepository(eq(GH_TOKEN), anyString(), anyString()))
                .thenReturn(repoJson);

        repositoryRepository.save(Repository.builder()
                .githubId(12345L).fullName("alice/dup").owner(testUser)
                .webhookSecret("enc").webhookId(1L).build());

        mockMvc.perform(connectRepo("""
                {"fullName":"alice/dup"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already connected")));
    }

    // --- GET /api/repos ---------------------------------------------------

    @Test
    void list_returnsOwnedRepos() throws Exception {
        repositoryRepository.save(Repository.builder()
                .githubId(1L).fullName("alice/a").owner(testUser)
                .webhookSecret("enc").webhookId(1L).build());
        repositoryRepository.save(Repository.builder()
                .githubId(2L).fullName("alice/b").owner(testUser)
                .webhookSecret("enc").webhookId(2L).build());

        mockMvc.perform(auth(get("/api/repos")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].fullName").value("alice/a"));
    }

    // --- GET /api/repos/{id} ---------------------------------------------

    @Test
    void get_returns404_whenNotOwnedByCaller() throws Exception {
        User other = userRepository.save(User.builder()
                .githubUsername("bob").githubId(99L)
                .accessToken(encryptionService.encrypt("gh_bob_token"))
                .build());
        Repository otherRepo = repositoryRepository.save(Repository.builder()
                .githubId(99L).fullName("bob/x").owner(other)
                .webhookSecret("enc").webhookId(1L).build());

        mockMvc.perform(auth(get("/api/repos/{id}", otherRepo.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_returns200_whenOwned() throws Exception {
        Repository repo = repositoryRepository.save(Repository.builder()
                .githubId(7L).fullName("alice/owned").owner(testUser)
                .webhookSecret("enc").webhookId(1L).build());

        mockMvc.perform(auth(get("/api/repos/{id}", repo.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("alice/owned"));
    }

    @Test
    void get_returnsSameAggregatesAsList() throws Exception {
        Repository repo = repositoryRepository.save(Repository.builder()
                .githubId(8L).fullName("alice/stats").owner(testUser)
                .webhookSecret("enc").webhookId(1L).build());
        pullRequestRepository.save(PullRequestEntity.builder()
                .repo(repo).githubPrNumber(1).status("reviewed")
                .reviewedAt(Instant.parse("2026-01-01T00:00:00Z")).build());
        qualityMetricRepository.save(com.automatedcodereviewtool.entity.QualityMetric.builder()
                .repo(repo).date(java.time.LocalDate.now(java.time.ZoneOffset.UTC))
                .avgQuality(new BigDecimal("87.50")).prsReviewed(1)
                .criticalCount(0).majorCount(0).minorCount(0).updatedAt(Instant.now())
                .build());

        mockMvc.perform(auth(get("/api/repos/{id}", repo.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPrsReviewed").value(1))
                .andExpect(jsonPath("$.lastReviewedAt").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.latestQualityScore").value(87.5));

        mockMvc.perform(auth(get("/api/repos")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].totalPrsReviewed").value(1))
                .andExpect(jsonPath("$[0].lastReviewedAt").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$[0].latestQualityScore").value(87.5));
    }

    // --- GET /api/metrics/quality-trend ----------------------------------

    @Test
    void qualityTrend_topAntiPattern_onlyCountsFindingsInsideTheWindow() throws Exception {
        Repository repo = repositoryRepository.save(Repository.builder()
                .githubId(10L).fullName("alice/trend").owner(testUser)
                .webhookSecret("enc").webhookId(1L).build());
        PullRequestEntity pr = pullRequestRepository.save(PullRequestEntity.builder()
                .repo(repo).githubPrNumber(3).status("reviewed").build());
        for (int i = 0; i < 3; i++) {
            com.automatedcodereviewtool.entity.Finding old = findingRepository.save(finding(pr, "READABILITY_MAGIC_NUMBER"));
            jdbc.update("UPDATE findings SET created_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(Instant.now().minus(java.time.Duration.ofDays(60))), old.getId());
        }
        findingRepository.save(finding(pr, "SECURITY_HARDCODED_SECRET"));

        mockMvc.perform(auth(get("/api/metrics/quality-trend")
                        .param("repo", repo.getId().toString())
                        .param("days", "7")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topAntiPattern").value("SECURITY_HARDCODED_SECRET"));

        mockMvc.perform(auth(get("/api/metrics/quality-trend")
                        .param("repo", repo.getId().toString())
                        .param("days", "90")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topAntiPattern").value("READABILITY_MAGIC_NUMBER"));
    }

    private static com.automatedcodereviewtool.entity.Finding finding(PullRequestEntity pr, String pattern) {
        return com.automatedcodereviewtool.entity.Finding.builder()
                .pullRequest(pr).filePath("a.py").lineStart(1).lineEnd(1)
                .antiPattern(pattern).category("SECURITY").severity("minor")
                .confidence(new BigDecimal("0.500")).build();
    }

    // --- GET /api/repos/{id}/prs -----------------------------------------

    @Test
    void listPrs_returnsSummaries_withoutRepositoryOrSecrets() throws Exception {
        String encryptedSecret = encryptionService.encrypt("webhook-secret-value");
        Repository repo = repositoryRepository.save(Repository.builder()
                .githubId(9L).fullName("alice/prs").owner(testUser)
                .webhookSecret(encryptedSecret).webhookId(1L).build());
        pullRequestRepository.save(PullRequestEntity.builder()
                .repo(repo).githubPrNumber(42).title("Add feature").authorGithub("bob")
                .headSha("abc123").status("reviewed").qualityScore(new BigDecimal("91.50"))
                .build());

        String json = mockMvc.perform(auth(get("/api/repos/{id}/prs", repo.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].githubPrNumber").value(42))
                .andExpect(jsonPath("$.content[0].qualityScore").value(91.5))
                .andExpect(jsonPath("$.content[0].repo").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(json)
                .doesNotContain(encryptedSecret)
                .doesNotContain(testUser.getAccessToken())
                .doesNotContain("webhookSecret")
                .doesNotContain("accessToken");
    }

    // --- reconnect -------------------------------------------------------

    @Test
    void connect_reactivatesRepoPreviouslyDisconnectedByCaller() throws Exception {
        Repository old = repositoryRepository.save(Repository.builder()
                .githubId(555L).fullName("alice/back").owner(testUser)
                .webhookSecret("old").webhookId(1L).isActive(false).build());
        org.mockito.Mockito.when(gitHubService.getRepository(eq(GH_TOKEN), anyString(), anyString()))
                .thenReturn(Map.of("id", 555L, "full_name", "alice/back", "private", false));
        org.mockito.Mockito.when(gitHubService.installWebhook(eq(GH_TOKEN), anyString(), anyString(), anyString()))
                .thenReturn(2L);

        mockMvc.perform(connectRepo("""
                {"fullName":"alice/back"}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(old.getId().toString()))
                .andExpect(jsonPath("$.active").value(true));

        Repository reloaded = repositoryRepository.findById(old.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.isActive()).isTrue();
        org.assertj.core.api.Assertions.assertThat(reloaded.getWebhookId()).isEqualTo(2L);
    }

    @Test
    void connect_reassignsInactiveRepoPreviouslyConnectedByAnotherAccount() throws Exception {
        User other = userRepository.findByGithubUsername("carol").orElseGet(() ->
                userRepository.save(User.builder()
                        .githubUsername("carol").githubId(4242L)
                        .accessToken(encryptionService.encrypt("gh_carol_token"))
                        .build()));
        Repository old = repositoryRepository.save(Repository.builder()
                .githubId(556L).fullName("carol/shared").owner(other)
                .webhookSecret("old").webhookId(1L).isActive(false).build());
        org.mockito.Mockito.when(gitHubService.getRepository(eq(GH_TOKEN), anyString(), anyString()))
                .thenReturn(Map.of("id", 556L, "full_name", "carol/shared", "private", false));
        org.mockito.Mockito.when(gitHubService.installWebhook(eq(GH_TOKEN), anyString(), anyString(), anyString()))
                .thenReturn(3L);

        mockMvc.perform(connectRepo("""
                {"fullName":"carol/shared"}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(old.getId().toString()));

        java.util.UUID ownerId = jdbc.queryForObject(
                "SELECT owner_id FROM repositories WHERE id = ?", java.util.UUID.class, old.getId());
        org.assertj.core.api.Assertions.assertThat(ownerId).isEqualTo(testUser.getId());
        org.assertj.core.api.Assertions.assertThat(
                repositoryRepository.findById(old.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void connect_rejectsRepoActivelyConnectedByAnotherAccount() throws Exception {
        User other = userRepository.findByGithubUsername("carol").orElseGet(() ->
                userRepository.save(User.builder()
                        .githubUsername("carol").githubId(4242L)
                        .accessToken(encryptionService.encrypt("gh_carol_token"))
                        .build()));
        repositoryRepository.save(Repository.builder()
                .githubId(557L).fullName("carol/live").owner(other)
                .webhookSecret("enc").webhookId(1L).build());
        org.mockito.Mockito.when(gitHubService.getRepository(eq(GH_TOKEN), anyString(), anyString()))
                .thenReturn(Map.of("id", 557L, "full_name", "carol/live", "private", false));

        mockMvc.perform(connectRepo("""
                {"fullName":"carol/live"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already connected")));
        org.mockito.Mockito.verify(gitHubService, org.mockito.Mockito.never())
                .installWebhook(anyString(), anyString(), anyString(), anyString());
    }

    // --- DELETE /api/repos/{id} ------------------------------------------

    @Test
    void disconnect_returns204_andMarksInactive() throws Exception {
        Repository repo = repositoryRepository.save(Repository.builder()
                .githubId(11L).fullName("alice/disco").owner(testUser)
                .webhookSecret("enc").webhookId(99L).build());

        mockMvc.perform(auth(delete("/api/repos/{id}", repo.getId())))
                .andExpect(status().isNoContent());

        Repository reloaded = repositoryRepository.findById(repo.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.isActive()).isFalse();
        // Regression: the webhook delete must use the decrypted token.
        org.mockito.Mockito.verify(gitHubService).deleteWebhook(GH_TOKEN, "alice/disco", 99L);
    }

    @Test
    void disconnect_returns404_whenNotOwned() throws Exception {
        User other = userRepository.save(User.builder()
                .githubUsername("eve").githubId(33L)
                .accessToken(encryptionService.encrypt("gh_eve_token"))
                .build());
        Repository otherRepo = repositoryRepository.save(Repository.builder()
                .githubId(33L).fullName("eve/y").owner(other)
                .webhookSecret("enc").webhookId(1L).build());

        mockMvc.perform(auth(delete("/api/repos/{id}", otherRepo.getId())))
                .andExpect(status().isNotFound());
    }

    // --- helpers ---------------------------------------------------------

    private MockHttpServletRequestBuilder connectRepo(String body) {
        return auth(post("/api/repos/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /**
     * Attach a User principal so {@code @AuthenticationPrincipal User user}
     * resolves to the test's domain entity. The controller signature
     * declares {@code User user} (not {@code UserDetails}), so the
     * principal in the SecurityContext must be the entity itself, not
     * a Spring Security UserDetails wrapper. We use {@code authentication()}
     * (instead of {@code user()}) to install a custom
     * {@link org.springframework.security.core.Authentication} whose
     * principal is the {@code User} entity.
     */
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder) {
        org.springframework.security.core.Authentication auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                testUser,
                null,
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")));
        return builder.with(authentication(auth));
    }
}