package com.codelens.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST /api/repos/connect}.
 *
 * <p>The repository is identified by its full GitHub name
 * ({@code owner/repo}) — we resolve it to a numeric ID via
 * {@code GitHubService} so we can later install the webhook
 * and persist encrypted secrets under that ID.</p>
 */
public record ConnectRepoRequest(
        @NotBlank
        @Pattern(regexp = "^[\\w.-]+/[\\w.-]+$",
                message = "must be in 'owner/repo' format")
        String fullName
) {
}
