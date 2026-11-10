package com.automatedcodereviewtool.controller;

import com.automatedcodereviewtool.config.SecurityConfig;
import com.automatedcodereviewtool.entity.Finding;
import com.automatedcodereviewtool.entity.PullRequestEntity;
import com.automatedcodereviewtool.entity.Repository;
import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.repository.FindingRepository;
import com.automatedcodereviewtool.service.ApiKeyService;
import com.automatedcodereviewtool.service.MlWorkerService;
import com.automatedcodereviewtool.security.ApiKeyAuthFilter;
import com.automatedcodereviewtool.security.AuthRateLimitFilter;
import com.automatedcodereviewtool.security.JwtAuthFilter;
import com.automatedcodereviewtool.security.JwtService;
import com.automatedcodereviewtool.logging.SecurityEventLogger;
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
            return new JwtAuthFilter(null, null, null, null) {
                @Override
                protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                                FilterChain chain) throws java.io.IOException, jakarta.servlet.ServletException {
                    // Build the actual User entity so controllers that use
                    // {@code @AuthenticationPrincipal User} (entity) receive
                    // a populated caller — see isOwnedBy().
                    com.automatedcodereviewtool.entity.User caller =
                            com.automatedcodereviewtool.entity.User.builder()
                                    .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                    .githubUsername(TEST_OWNER)
                                    .accessToken("dummy")
                                    .build();
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(
                                    caller, "n/a",
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
                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
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
    // SecurityConfig now also requires ApiKeyAuthFilter — mock it so the
    // MVC slice doesn't try to wire Redis / ApiKeyRepository.
    @MockBean ApiKeyAuthFilter apiKeyAuthFilter;
    @MockBean ApiKeyService apiKeyService;
    // SecurityConfig also wires AuthRateLimitFilter (needs Redis) — mock
    // it as a pass-through alongside ApiKeyAuthFilter.
    @MockBean AuthRateLimitFilter authRateLimitFilter;
    @MockBean SecurityEventLogger securityEventLogger;

    @org.junit.jupiter.api.BeforeEach
    void stubApiKeyFilter() throws Exception {
        // The ApiKeyAuthFilter mock would otherwise do nothing
        // (Mockito returns null for unstubbed void-returning methods)
        // and stall the security chain. Make it a pass-through.
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

    // --- POST /api/scan/file -------------------------------------------------

    @Test
    void scanFile_returns200_withFindings() throws Exception {
        UUID findingId = UUID.randomUUID();
        var mlResp = new com.automatedcodereviewtool.dto.MlReviewResponse(
                List.of(new com.automatedcodereviewtool.dto.MlFinding(
                        "app/Main.java", null,
                        10, 20,
                        "GodClass", "structural",
                        "high", new BigDecimal("0.90"),
                        "too big")),
                new BigDecimal("72.0"),
                0,
                0,
                "fallback", "v1", "1.0.0");

        when(mlWorkerService.reviewFile("public class A {}", "java", "src/A.java")).thenReturn(mlResp);

        String body = """
                {"content":"public class A {}","language":"java","filePath":"src/A.java"}
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
    void scanFile_returns400_onUnsupportedLanguage() throws Exception {
        String body = """
                {"content":"const a: number = 1;","language":"typescript"}
                """;

        mockMvc.perform(post("/api/scan/file")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        org.mockito.Mockito.verifyNoInteractions(mlWorkerService);
    }

    @Test
    void scanFile_returns400_whenMlWorkerRejects() throws Exception {
        when(mlWorkerService.reviewFile(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.isNull()))
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

    static final UUID CALLER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private Finding ownedFinding(UUID findingId) {
        PullRequestEntity pr = PullRequestEntity.builder()
                .id(UUID.randomUUID()).repo(testRepo("o/r"))
                .githubPrNumber(1).title("t").status("reviewed")
                .build();
        Finding existing = Finding.builder()
                .id(findingId).pullRequest(pr)
                .antiPattern("SECURITY_WEAK_CRYPTO").category("SECURITY")
                .severity("major").confidence(new BigDecimal("0.9"))
                .explanation("md5").build();
        when(findingRepository.findOwnedById(findingId, CALLER_ID)).thenReturn(Optional.of(existing));
        return existing;
    }

    private org.springframework.test.web.servlet.ResultActions action(String json) throws Exception {
        return mockMvc.perform(post("/api/scan/action")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .accept(MediaType.APPLICATION_JSON));
    }

    @Test
    void recordAction_dismissWithReason_storesTrimmedReasonAndAuditFields() throws Exception {
        UUID findingId = UUID.randomUUID();
        Finding existing = ownedFinding(findingId);
        Instant before = Instant.now();

        action("""
                {"findingId":"%s","action":"dismiss","reason":"  legacy module, scheduled for removal  "}
                """.formatted(findingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.findingId").value(findingId.toString()))
                .andExpect(jsonPath("$.status").value("dismissed"))
                .andExpect(jsonPath("$.reason").value("legacy module, scheduled for removal"))
                .andExpect(jsonPath("$.triagedBy").value(TEST_OWNER))
                .andExpect(jsonPath("$.triagedAt").isNotEmpty());

        org.assertj.core.api.Assertions.assertThat(existing.getStatus()).isEqualTo("dismissed");
        org.assertj.core.api.Assertions.assertThat(existing.getTriageReason())
                .isEqualTo("legacy module, scheduled for removal");
        org.assertj.core.api.Assertions.assertThat(existing.getTriagedBy()).isEqualTo(CALLER_ID);
        org.assertj.core.api.Assertions.assertThat(existing.getTriagedAt()).isAfterOrEqualTo(before);
        org.mockito.Mockito.verify(findingRepository).save(existing);
    }

    @Test
    void recordAction_falsePositiveWithReason_returns200() throws Exception {
        UUID findingId = UUID.randomUUID();
        ownedFinding(findingId);

        action("""
                {"findingId":"%s","action":"false_positive","reason":"md5 is used as a cache key, not for security"}
                """.formatted(findingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("false_positive"));
    }

    @Test
    void recordAction_requiresReasonForDismissAndFalsePositive() throws Exception {
        UUID findingId = UUID.randomUUID();
        ownedFinding(findingId);

        for (String body : new String[]{
                "{\"findingId\":\"%s\",\"action\":\"dismiss\"}",
                "{\"findingId\":\"%s\",\"action\":\"dismiss\",\"reason\":\"   \"}",
                "{\"findingId\":\"%s\",\"action\":\"false_positive\"}",
                "{\"findingId\":\"%s\",\"action\":\"false_positive\",\"reason\":\"\"}"}) {
            action(body.formatted(findingId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("reason:")));
        }
        org.mockito.Mockito.verify(findingRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void recordAction_rejectsReasonLongerThan500CharsAfterTrim() throws Exception {
        UUID findingId = UUID.randomUUID();
        ownedFinding(findingId);

        action("""
                {"findingId":"%s","action":"dismiss","reason":"   %s   "}
                """.formatted(findingId, "x".repeat(500)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("x".repeat(500)));

        action("""
                {"findingId":"%s","action":"dismiss","reason":"%s"}
                """.formatted(findingId, "x".repeat(501)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void recordAction_acceptFixAndReopen_doNotRequireReason() throws Exception {
        UUID findingId = UUID.randomUUID();
        Finding existing = ownedFinding(findingId);
        existing.setTriageReason("old reason");

        action("{\"findingId\":\"%s\",\"action\":\"accept\"}".formatted(findingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));
        action("{\"findingId\":\"%s\",\"action\":\"FIX\"}".formatted(findingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("fixed"));
        action("{\"findingId\":\"%s\",\"action\":\"reopen\"}".formatted(findingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("open"))
                .andExpect(jsonPath("$.reason").doesNotExist());

        org.assertj.core.api.Assertions.assertThat(existing.getStatus()).isEqualTo("open");
        org.assertj.core.api.Assertions.assertThat(existing.getTriageReason()).isNull();
        org.assertj.core.api.Assertions.assertThat(existing.getTriagedAt()).isNotNull();
    }

    @Test
    void recordAction_returns404_whenFindingUnknownOrNotOwned() throws Exception {
        // findOwnedById applies the owner predicate in SQL, so a foreign
        // finding and a missing one look identical here (see
        // OwnershipIsolationTest for the database-backed proof).
        when(findingRepository.findOwnedById(any(), any())).thenReturn(Optional.empty());

        action("""
                {"findingId":"%s","action":"dismiss","reason":"noise"}
                """.formatted(UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        org.mockito.Mockito.verify(findingRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void recordAction_returns400_onInvalidAction() throws Exception {
        UUID findingId = UUID.randomUUID();
        ownedFinding(findingId);

        action("""
                {"findingId":"%s","action":"delete_forever"}
                """.formatted(findingId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        org.mockito.Mockito.verify(findingRepository, org.mockito.Mockito.never()).save(any());
    }
}
