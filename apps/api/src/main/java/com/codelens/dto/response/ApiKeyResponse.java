package com.codelens.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * Public view of an API key.
 *
 * <p>The full key value is <strong>never</strong> returned here — only
 * the prefix (e.g. {@code cl_live_abc1...}) that we use for
 * index-time lookups in {@code ApiKeyAuthFilter}. The plaintext
 * key is returned exactly once, in {@link CreatedApiKeyResponse},
 * right after creation.</p>
 */
public record ApiKeyResponse(
        UUID id,
        String label,
        String prefix,
        Instant createdAt,
        Instant lastUsedAt
) {
}