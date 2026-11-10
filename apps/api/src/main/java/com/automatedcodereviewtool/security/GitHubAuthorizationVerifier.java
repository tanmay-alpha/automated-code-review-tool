package com.automatedcodereviewtool.security;

import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.service.GitHubService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Checks whether the GitHub OAuth grant stored for a user still works.
 *
 * <p>GitHub does not notify OAuth apps when a user revokes the app or the
 * token expires, so session refresh asks {@code GET /user} with the stored
 * token. Only an explicit {@code 401} from GitHub (or a stored token that can
 * no longer be decrypted) counts as {@link Status#REVOKED}; timeouts, rate
 * limits and GitHub outages are {@link Status#UNKNOWN} so a GitHub incident
 * never logs every user out.</p>
 */
@Component
public class GitHubAuthorizationVerifier {

    public enum Status { VALID, REVOKED, UNKNOWN }

    private static final Logger log = LoggerFactory.getLogger(GitHubAuthorizationVerifier.class);

    /** Upper bound added to a refresh by this check. */
    static final Duration CHECK_TIMEOUT = Duration.ofSeconds(5);

    private final GitHubService gitHubService;
    private final EncryptionService encryptionService;
    private final Executor executor = Executors.newVirtualThreadPerTaskExecutor();

    public GitHubAuthorizationVerifier(GitHubService gitHubService, EncryptionService encryptionService) {
        this.gitHubService = gitHubService;
        this.encryptionService = encryptionService;
    }

    public Status check(User user) {
        String token;
        try {
            token = encryptionService.decrypt(user.getAccessToken());
        } catch (RuntimeException undecryptable) {
            // Encryption key rotated or row corrupted: the grant is unusable.
            log.warn("Stored GitHub token for user {} cannot be decrypted; re-authentication required", user.getId());
            return Status.REVOKED;
        }
        if (token == null || token.isBlank()) {
            return Status.REVOKED;
        }
        try {
            CompletableFuture.supplyAsync(() -> gitHubService.getCurrentUser(token), executor)
                    .get(CHECK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return Status.VALID;
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof WebClientResponseException response
                    && response.getStatusCode().value() == 401) {
                log.info("GitHub authorization for user {} was revoked or expired", user.getId());
                return Status.REVOKED;
            }
            log.warn("GitHub authorization check for user {} inconclusive: {}",
                    user.getId(), failed.getCause() == null ? "unknown" : failed.getCause().getClass().getSimpleName());
            return Status.UNKNOWN;
        } catch (TimeoutException slow) {
            log.warn("GitHub authorization check for user {} timed out", user.getId());
            return Status.UNKNOWN;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Status.UNKNOWN;
        }
    }
}
