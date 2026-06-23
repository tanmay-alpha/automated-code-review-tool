package com.codelens.controller;

import com.codelens.entity.Repository;
import com.codelens.entity.User;
import com.codelens.repository.RepositoryRepository;
import com.codelens.repository.UserRepository;
import com.codelens.security.ApiKeyAuthFilter;
import com.codelens.security.JwtAuthFilter;
import com.codelens.security.EncryptionService;
import com.codelens.service.ApiKeyService;
import com.codelens.service.GitHubService;
import com.codelens.service.RepoService;
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

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
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
 * <p>The {@link com.codelens.security.ApiKeyAuthFilter} is replaced with
 * a Mockito mock so we don't need Redis; the JWT auth is bypassed by
 * attaching an authenticated principal to each request via
 * {@link com.codelens.security.test.SecurityMockMvcRequestPostProcessors#user}.
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
    @Autowired EncryptionService encryptionService;
    @Autowired RepoService repoService; // sanity: confirm wiring works

    @MockBean GitHubService gitHubService;
    @MockBean ApiKeyAuthFilter apiKeyAuthFilter;
    @MockBean ApiKeyService apiKeyService;
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
    }

    private User testUser;

    @BeforeEach
    void setUp() {
        // Wipe any repos left over from prior tests so each test
        // starts with a clean slate. Users are not deleted (we
        // want foreign keys to remain valid).
        repositoryRepository.deleteAll();

        testUser = userRepository.findByGithubUsername("alice").orElseGet(() ->
                userRepository.save(User.builder()
                        .githubUsername("alice")
                        .githubId(12345L)
                        .avatarUrl("https://a.c/a.png")
                        .accessToken(encryptionService.encrypt("gh_test_token"))
                        .build()));
    }

    // --- POST /api/repos/connect ------------------------------------------

    @Test
    void connect_returns201_andStoresRepo() throws Exception {
        Map<String, Object> repoJson = Map.of(
                "id", 999, "full_name", "alice/cool", "private", false,
                "description", "a cool repo");
        org.mockito.Mockito.when(gitHubService.getRepository(anyString(), anyString(), anyString()))
                .thenReturn(repoJson);
        org.mockito.Mockito.when(gitHubService.installWebhook(anyString(), anyString(), anyString(), anyString()))
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
        org.mockito.Mockito.when(gitHubService.getRepository(anyString(), anyString(), anyString()))
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
        org.mockito.Mockito.when(gitHubService.getRepository(anyString(), anyString(), anyString()))
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