package com.codelens.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Body for {@code POST /api/scan/action} — user disposition on a single finding.
 *
 * <p>{@code action} is one of {@code "accept"}, {@code "dismiss"},
 * {@code "fix"} — semantically equivalent but the model/audit log
 * keeps the verb to understand developer intent.</p>
 */
public record FindingActionRequest(
        @NotNull UUID findingId,
        @NotBlank @Size(max = 20) String action
) {
}