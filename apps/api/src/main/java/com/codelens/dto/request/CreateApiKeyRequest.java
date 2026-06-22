package com.codelens.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /api/auth/api-keys}.
 */
public record CreateApiKeyRequest(
        @NotBlank @Size(max = 60) String label
) {
}
