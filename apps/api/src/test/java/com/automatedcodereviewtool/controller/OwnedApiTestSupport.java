package com.automatedcodereviewtool.controller;

import com.automatedcodereviewtool.entity.Finding;
import com.automatedcodereviewtool.entity.PullRequestEntity;
import com.automatedcodereviewtool.entity.Repository;
import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.repository.FindingRepository;
import com.automatedcodereviewtool.repository.PullRequestRepository;
import com.automatedcodereviewtool.repository.RepositoryRepository;
import com.automatedcodereviewtool.repository.UserRepository;
import com.automatedcodereviewtool.security.ApiKeyAuthFilter;
import com.automatedcodereviewtool.security.AuthRateLimitFilter;
import com.automatedcodereviewtool.security.EncryptionService;
import com.automatedcodereviewtool.security.JwtAuthFilter;
import com.automatedcodereviewtool.service.ApiKeyService;
import com.automatedcodereviewtool.service.GitHubService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * H2-backed {@code @SpringBootTest} base for owner-scoped API tests: the
 * real controllers, services, repositories and Flyway H2 mirror, with the
 * Redis-backed filters and GitHub client mocked. The JWT filter is a
 * pass-through; each request carries a {@link User} principal via
 * {@link #as(User, MockHttpServletRequestBuilder)}.
 *
 * <p>The in-memory database is shared with other test classes, so every
 * fixture uses random GitHub ids and unique logins.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class OwnedApiTestSupport {

    @Autowired protected MockMvc mockMvc;
    @Autowired protected UserRepository userRepository;
    @Autowired protected RepositoryRepository repositoryRepository;
    @Autowired protected PullRequestRepository pullRequestRepository;
    @Autowired protected FindingRepository findingRepository;
    @Autowired protected EncryptionService encryptionService;
    @Autowired protected JdbcTemplate jdbc;

    @MockBean protected GitHubService gitHubService;
    @MockBean ApiKeyAuthFilter apiKeyAuthFilter;
    @MockBean ApiKeyService apiKeyService;
    @MockBean AuthRateLimitFilter authRateLimitFilter;
    @MockBean JwtAuthFilter jwtAuthFilter;

    @BeforeEach
    void passThroughFilters() throws Exception {
        for (jakarta.servlet.Filter filter : List.of(apiKeyAuthFilter, authRateLimitFilter, jwtAuthFilter)) {
            doAnswer(inv -> {
                ServletRequest req = inv.getArgument(0);
                ServletResponse res = inv.getArgument(1);
                FilterChain chain = inv.getArgument(2);
                chain.doFilter(req, res);
                return null;
            }).when(filter).doFilter(any(ServletRequest.class), any(ServletResponse.class), any(FilterChain.class));
        }
    }

    protected static long randomGithubId() {
        return ThreadLocalRandom.current().nextLong(1_000_000L, Long.MAX_VALUE / 2);
    }

    protected User newUser(String loginPrefix) {
        return userRepository.save(User.builder()
                .githubId(randomGithubId())
                .githubUsername(loginPrefix + "-" + Long.toHexString(randomGithubId()))
                .accessToken(encryptionService.encrypt("gh_token_" + loginPrefix))
                .build());
    }

    protected Repository newRepo(User owner, String fullName) {
        return repositoryRepository.save(Repository.builder()
                .githubId(randomGithubId())
                .fullName(fullName)
                .owner(owner)
                .webhookId(1L)
                .webhookSecret("enc")
                .build());
    }

    protected PullRequestEntity newPr(Repository repo, int number, String status) {
        return pullRequestRepository.save(PullRequestEntity.builder()
                .repo(repo).githubPrNumber(number).status(status).title("PR " + number)
                .build());
    }

    protected Finding newFinding(PullRequestEntity pr, String antiPattern, String status, String engine) {
        return findingRepository.save(Finding.builder()
                .pullRequest(pr).filePath("src/app.py").lineStart(1).lineEnd(1)
                .antiPattern(antiPattern).category("SECURITY").severity("major")
                .confidence(new BigDecimal("0.800"))
                .status(status)
                .triageReason("false_positive".equals(status) || "dismissed".equals(status) ? "fixture" : null)
                .engine(engine)
                .build());
    }

    /** Attach {@code user} as the authenticated principal (the entity, as JwtAuthFilter does). */
    protected static MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder builder) {
        return builder.with(authentication(new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_USER")))));
    }
}
