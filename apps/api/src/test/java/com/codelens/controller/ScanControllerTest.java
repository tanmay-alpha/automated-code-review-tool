package com.codelens.controller;

import com.codelens.config.SecurityConfig;
import com.codelens.entity.Finding;
import com.codelens.entity.PullRequestEntity;
import com.codelens.entity.Repository;
import com.codelens.entity.User;
import com.codelens.repository.FindingRepository;
import com.codelens.service.MlWorkerService;
import com.codelens.security.JwtAuthFilter;
import com.codelens.security.JwtService;
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
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice tests for {@link ScanController}.
 *
 * <p>Both endpoints are protected — imports {@link SecurityConfig}
 * and uses the same pass-through {@link JwtAuthFilter} as
 * {@link ReviewControllerTest}.</p>
 */
@WebMvcTest(controllers = ScanController.class)
@Import({SecurityConfig.class, ScanControllerTest.PassThroughFilter.class})
class ScanControllerTest {

    /** Username that test repositories should be owned by (matches the
     *  pass-through filter's principal — keeps ownership checks happy). */
    static final String TEST_OWNER = "test-user";

    @org.springframework.boot.test.context.TestConfiguration
    static class PassThroughFilter {
        @org.springframework.context.annotation.Bean
        JwtAuthFilter jwtAuthFilter() {
            return new JwtAuthFilter(null) {
                @Override
                protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                                FilterChain chain) throws java.io.IOException, jakarta.servlet.ServletException {
                    // Use a real UserDetails as the principal so
                    // @AuthenticationPrincipal UserDetails works in the controller.
                    UserDetails principal = org.springframework.security.core.userdetails.User
                            .withUsername(TEST_OWNER)
                            .password("n/a")
                            .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                            .build();
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(
                                    principal, "n/a",
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

    static Repository testRepo(String name) {
        User owner = User.builder()
                .githubUsername(TEST_OWNER)
                .accessToken("dummy")
                .build();
        return Repository.builder()
                .fullName(name)
                .owner(owner)
                .build();
    }

    @Autowired MockMvc mockMvc;

    @MockBean FindingRepository findingRepository;
    @MockBean MlWorkerService mlWorkerService;
    @MockBean JwtService jwtService;

    // --- POST /api/scan/file -------------------------------------------------

    @Test
    void scanFile_returns200_withFindings() throws Exception {
        UUID findingId = UUID.randomUUID();
        var mlResp = new com.codelens.dto.MlReviewResponse(
                List.of(new com.codelens.dto.MlFinding(
                        10, 20,
                        "GodClass", "structural",
                        "high", new BigDecimal("0.90"),
                        "too big")),
                new BigDecimal("72.0"),
                0,
                0);

        when(mlWorkerService.reviewFile("public class A {}", "java")).thenReturn(mlResp);

        String body = """
                {"content":"public class A {}","language":"java"}
                """;

        mockMvc.perform(post("/api/scan/file")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.findings").isArray())
                .andExpect(jsonPath("$.findings.length()").value(1))
                .andExpect(jsonPath("$.findings[0].antiPattern").value("GodClass"))
                .andExpect(jsonPath("$.qualityScore").value(72.0));
    }

    @Test
    void scanFile_returns400_onBlankContent() throws Exception {
        String body = """
                {"content":"   ","language":"java"}
                """;

        mockMvc.perform(post("/api/scan/file")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void scanFile_returns400_whenMlWorkerRejects() throws Exception {
        when(mlWorkerService.reviewFile(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalArgumentException("not a real diff"));

        String body = """
                {"content":"not a diff","language":"java"}
                """;

        mockMvc.perform(post("/api/scan/file")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // --- POST /api/scan/action ----------------------------------------------

    @Test
    void recordAction_updatesFindingAndReturns200() throws Exception {
        UUID findingId = UUID.randomUUID();
        UUID prId = UUID.randomUUID();

        PullRequestEntity pr = PullRequestEntity.builder()
                .id(prId).repo(testRepo("o/r"))
                .githubPrNumber(1).title("t").status("reviewed")
                .build();

        Finding existing = Finding.builder()
                .id(findingId).pullRequest(pr)
                .antiPattern("GodClass").category("structural")
                .severity("high").confidence(new BigDecimal("0.9"))
                .explanation("big").build();

        when(findingRepository.findById(findingId)).thenReturn(Optional.of(existing));

        String body = """
                {"findingId":"%s","action":"dismiss"}
                """.formatted(findingId);

        mockMvc.perform(post("/api/scan/action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("dismiss"))
                .andExpect(jsonPath("$.findingId").value(findingId.toString()))
                .andExpect(jsonPath("$.status").value("dismiss"));
    }

    @Test
    void recordAction_returns404_whenFindingUnknown() throws Exception {
        UUID randomId = UUID.randomUUID();
        when(findingRepository.findById(any())).thenReturn(Optional.empty());

        String body = """
                {"findingId":"%s","action":"dismiss"}
                """.formatted(randomId);

        mockMvc.perform(post("/api/scan/action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void recordAction_returns400_onInvalidAction() throws Exception {
        UUID findingId = UUID.randomUUID();
        UUID prId = UUID.randomUUID();

        PullRequestEntity pr = PullRequestEntity.builder()
                .id(prId).repo(testRepo("o/r"))
                .githubPrNumber(1).title("t").status("reviewed")
                .build();

        Finding existing = Finding.builder()
                .id(findingId).pullRequest(pr)
                .antiPattern("GodClass").category("structural")
                .severity("high").confidence(new BigDecimal("0.9"))
                .explanation("big").build();

        when(findingRepository.findById(findingId)).thenReturn(Optional.of(existing));

        String body = """
                {"findingId":"%s","action":"delete_forever"}
                """.formatted(findingId);

        mockMvc.perform(post("/api/scan/action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recordAction_returns404_whenCallerDoesNotOwnRepo() throws Exception {
        UUID findingId = UUID.randomUUID();
        UUID prId = UUID.randomUUID();

        // Owner is a different user, not TEST_OWNER (the principal in the pass-through filter)
        User otherOwner = User.builder()
                .githubUsername("attacker")
                .accessToken("dummy")
                .build();
        Repository otherRepo = Repository.builder()
                .fullName("o/r")
                .owner(otherOwner)
                .build();

        PullRequestEntity pr = PullRequestEntity.builder()
                .id(prId).repo(otherRepo)
                .githubPrNumber(1).title("t").status("reviewed")
                .build();

        Finding existing = Finding.builder()
                .id(findingId).pullRequest(pr)
                .antiPattern("GodClass").category("structural")
                .severity("high").confidence(new BigDecimal("0.9"))
                .explanation("big").build();

        when(findingRepository.findById(findingId)).thenReturn(Optional.of(existing));

        String body = """
                {"findingId":"%s","action":"dismiss"}
                """.formatted(findingId);

        mockMvc.perform(post("/api/scan/action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());   // 404, not 403, to avoid leaking existence
    }
}