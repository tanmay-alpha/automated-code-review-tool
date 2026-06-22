package com.codelens.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * Returned <strong>only once</strong>, at key creation. The full
 * plaintext key is included so the caller can paste it into their
 * VS Code extension. After this response, only the prefix is stored.
 */
public record CreatedApiKeyResponse(
        UUID id,
        String label,
        String prefix,
        String key,
        Instant createdAt
) {
}
