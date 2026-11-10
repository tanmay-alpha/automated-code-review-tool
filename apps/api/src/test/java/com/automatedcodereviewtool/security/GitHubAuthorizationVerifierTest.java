package com.automatedcodereviewtool.security;

import com.automatedcodereviewtool.dto.GitHubUserInfo;
import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.service.GitHubService;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitHubAuthorizationVerifierTest {

    private static final String KEY = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=";

    private final EncryptionService encryption = initialized(new EncryptionService(KEY));
    private final GitHubService gitHub = mock(GitHubService.class);
    private final GitHubAuthorizationVerifier verifier = new GitHubAuthorizationVerifier(gitHub, encryption);

    private static EncryptionService initialized(EncryptionService service) {
        service.init();
        return service;
    }

    private User userWithToken(String token) {
        return User.builder().id(UUID.randomUUID()).githubUsername("u")
                .accessToken(token == null ? null : encryption.encrypt(token)).build();
    }

    @Test
    void workingGrantIsValid() {
        when(gitHub.getCurrentUser("gho_ok")).thenReturn(new GitHubUserInfo(1L, "u", null));

        assertThat(verifier.check(userWithToken("gho_ok"))).isEqualTo(GitHubAuthorizationVerifier.Status.VALID);
    }

    @Test
    void github401MeansRevoked() {
        when(gitHub.getCurrentUser("gho_revoked"))
                .thenThrow(WebClientResponseException.create(401, "Unauthorized", null, null, null));

        assertThat(verifier.check(userWithToken("gho_revoked"))).isEqualTo(GitHubAuthorizationVerifier.Status.REVOKED);
    }

    @Test
    void outagesAndRateLimitsAreInconclusive() {
        when(gitHub.getCurrentUser("gho_503"))
                .thenThrow(WebClientResponseException.create(503, "Unavailable", null, null, null));
        when(gitHub.getCurrentUser("gho_403"))
                .thenThrow(WebClientResponseException.create(403, "rate limited", null, null, null));
        when(gitHub.getCurrentUser("gho_io")).thenThrow(new IllegalStateException("connection reset"));

        assertThat(verifier.check(userWithToken("gho_503"))).isEqualTo(GitHubAuthorizationVerifier.Status.UNKNOWN);
        assertThat(verifier.check(userWithToken("gho_403"))).isEqualTo(GitHubAuthorizationVerifier.Status.UNKNOWN);
        assertThat(verifier.check(userWithToken("gho_io"))).isEqualTo(GitHubAuthorizationVerifier.Status.UNKNOWN);
    }

    @Test
    void undecryptableOrMissingTokenIsRevokedWithoutCallingGitHub() {
        User corrupted = User.builder().id(UUID.randomUUID()).accessToken("not-base64-ciphertext!").build();

        assertThat(verifier.check(corrupted)).isEqualTo(GitHubAuthorizationVerifier.Status.REVOKED);
        assertThat(verifier.check(userWithToken(null))).isEqualTo(GitHubAuthorizationVerifier.Status.REVOKED);
        verify(gitHub, never()).getCurrentUser(anyString());
    }
}
