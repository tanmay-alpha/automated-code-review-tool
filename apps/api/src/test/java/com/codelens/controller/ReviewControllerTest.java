package com.codelens.controller;

import com.codelens.config.SecurityConfig;
import com.codelens.entity.Finding;
import com.codelens.entity.PullRequestEntity;
import com.codelens.entity.Repository;
import com.codelens.entity.User;
import com.codelens.repository.FindingRepository;
import com.codelens.repository.PullRequestRepository;
import com.codelens.security.ApiKeyAuthFilter;
import com.codelens.security.AuthRateLimitFilter;
import com.codelens.security.JwtAuthFilter;
import com.codelens.security.JwtService;
import com.codelens.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice tests for {@link ReviewController}.
 *
 * <p>Imports the production {@link SecurityConfig} so the real
 * SecurityFilterChain runs; stubs {@link JwtAuthFilter} as a
 * pass-through that pre-populates the security context so we don't
 * have to wire JwtService.</p>
 */
@WebMvcTest(controllers = ReviewController.class)
@Import({SecurityConfig.class, ReviewControllerTest.PassThroughFilter.class})
class ReviewControllerTest {

    @org.springframework.boot.test.context.TestConfiguration
    static class PassThroughFilter {
        @org.springframework.context.annotation.Bean
        JwtAuthFilter jwtAuthFilter() {
            return new JwtAuthFilter(null) {
                @Override
                protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                                FilterChain chain) throws java.io.IOException, jakarta.servlet.ServletException {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(
                                    "test-user", "n/a",
                                    List.of(new SimpleGrantedAuthority("ROLE_USER"))));
                    try {
                        chain.doFilter(req, res);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }
            };
        }
    }

    @Autowired MockMvc mockMvc;

    @MockBean PullRequestRepository pullRequestRepository;
    @MockBean FindingRepository findingRepository;
    // SecurityConfig requires JwtAuthFilter; mock JwtService so the bean graph resolves.
    @MockBean JwtService jwtService;
    // SecurityConfig also now wires ApiKeyAuthFilter — mock it so the
    // MVC slice doesn't try to load Redis / ApiKeyRepository.
    @MockBean ApiKeyAuthFilter apiKeyAuthFilter;
    @MockBean ApiKeyService apiKeyService;
    // SecurityConfig also wires AuthRateLimitFilter (needs Redis) — mock
    // it as a pass-through alongside ApiKeyAuthFilter.
    @MockBean AuthRateLimitFilter authRateLimitFilter;

    @org.junit.jupiter.api.BeforeEach
    void stubApiKeyFilter() throws Exception {
        // The mock would otherwise no-op (Mockito returns null on
        // unstubbed void-returning methods) and stall the chain.
        doAnswer(inv -> {
            jakarta.servlet.ServletRequest req = inv.getArgument(0);
            jakarta.servlet.ServletResponse res = inv.getArgument(1);
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(req, res);
            return null;
        }).when(apiKeyAuthFilter).doFilter(
                org.mockito.ArgumentMatchers.any(jakarta.servlet.ServletRequest.class),
                org.mockito.ArgumentMatchers.any(jakarta.servlet.ServletResponse.class),
                org.mockito.ArgumentMatchers.any(FilterChain.class));
        doAnswer(inv -> {
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

    @Test
    void getReview_returns200_withAllFields() throws Exception {
        UUID prId = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();
        UUID findingId1 = UUID.randomUUID();
        UUID findingId2 = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        Repository repo = Repository.builder().id(repoId).fullName("o/r").build();
        User owner = User.builder()
                .id(userId).githubUsername("alice").avatarUrl("https://a.c/a.png")
                .build();
        repo.setOwner(owner);

        PullRequestEntity pr = PullRequestEntity.builder()
                .id(prId).repo(repo).githubPrNumber(42).title("PR")
                .authorGithub("alice").status("reviewed")
                .qualityScore(new BigDecimal("85.50"))
                .headSha("abc123").githubPrUrl("https://g.co/p/42")
                .createdAt(Instant.now()).reviewedAt(Instant.now())
                .build();

        Finding f1 = Finding.builder().id(findingId1).pullRequest(pr)
                .antiPattern("GodClass").category("structural")
                .severity("high").confidence(new BigDecimal("0.92"))
                .explanation("too big").build();
        Finding f2 = Finding.builder().id(findingId2).pullRequest(pr)
                .antiPattern("MagicNumber").category("readability")
                .severity("low").confidence(new BigDecimal("0.80"))
                .explanation("42 is magic").build();

        when(pullRequestRepository.findById(prId)).thenReturn(Optional.of(pr));
        when(findingRepository.findAllByPullRequestId(prId)).thenReturn(List.of(f1, f2));

        mockMvc.perform(get("/api/reviews/{prId}", prId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(prId.toString()))
                .andExpect(jsonPath("$.githubPrNumber").value(42))
                .andExpect(jsonPath("$.status").value("reviewed"))
                .andExpect(jsonPath("$.qualityScore").value(85.50))
                .andExpect(jsonPath("$.findings").isArray())
                .andExpect(jsonPath("$.findings.length()").value(2))
                .andExpect(jsonPath("$.findings[0].antiPattern").value("GodClass"))
                .andExpect(jsonPath("$.repoFullName").value("o/r"))
                .andExpect(jsonPath("$.repoOwnerLogin").value("alice"));
    }

    @Test
    void getReview_returns404_whenPrNotFound() throws Exception {
        when(pullRequestRepository.findById(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/reviews/{prId}", UUID.randomUUID())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void getReview_emptyFindingsList() throws Exception {
        UUID prId = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();
        PullRequestEntity pr = PullRequestEntity.builder()
                .id(prId).repo(Repository.builder().id(repoId).fullName("o/r").build())
                .githubPrNumber(1).title("t").status("reviewed")
                .build();

        when(pullRequestRepository.findById(prId)).thenReturn(Optional.of(pr));
        when(findingRepository.findAllByPullRequestId(prId)).thenReturn(List.of());

        mockMvc.perform(get("/api/reviews/{prId}", prId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.findings").isArray())
                .andExpect(jsonPath("$.findings.length()").value(0));
    }
}