package com.automatedcodereviewtool.security;

import com.automatedcodereviewtool.dto.MlReviewResponse;
import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.repository.UserRepository;
import com.automatedcodereviewtool.service.ApiKeyService;
import com.automatedcodereviewtool.service.MlWorkerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/scan/file} with a real API key through the real
 * {@link ApiKeyAuthFilter} and H2 database — the path the VS Code
 * extension and GitHub Action use. Only Redis and the ML worker are mocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiKeyScanIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ApiKeyService apiKeyService;
    @Autowired private UserRepository userRepository;

    @MockBean private StringRedisTemplate redis;
    @MockBean private MlWorkerService mlWorkerService;

    private String apiKey;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);

        User user = userRepository.findByGithubUsername("key-owner").orElseGet(() ->
                userRepository.save(User.builder()
                        .githubId(777_001L)
                        .githubUsername("key-owner")
                        .accessToken("encrypted")
                        .build()));
        apiKey = apiKeyService.createKey(user, "vscode").plaintext();

        when(mlWorkerService.reviewFile("x = 1\n", "python", "a.py")).thenReturn(
                new MlReviewResponse(List.of(), new BigDecimal("100"), 3, 1,
                        "fallback", "rules-v1", "1.0.0"));
    }

    @Test
    void validKeyIsAuthenticated() throws Exception {
        mockMvc.perform(post("/api/scan/file")
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"x = 1\\n\",\"language\":\"python\",\"filePath\":\"a.py\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qualityScore").value(100));
    }

    @Test
    void wrongKeyIsRejected() throws Exception {
        char last = apiKey.charAt(apiKey.length() - 1);
        String forged = apiKey.substring(0, apiKey.length() - 1) + (last == '0' ? '1' : '0');

        mockMvc.perform(post("/api/scan/file")
                        .header("Authorization", "Bearer " + forged)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"x = 1\\n\",\"language\":\"python\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("API_KEY_INVALID"));
    }
}
