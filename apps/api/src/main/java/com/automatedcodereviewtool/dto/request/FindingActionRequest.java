package com.automatedcodereviewtool.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Body for {@code POST /api/scan/action} — triage decision on one finding.
 *
 * <p>{@code action} is one of {@code accept}, {@code dismiss}, {@code fix},
 * {@code false_positive}, {@code reopen}. {@code reason} is trimmed and
 * must be at most 500 characters afterwards; it is required for
 * {@code dismiss} and {@code false_positive} (checked by the controller,
 * because the rule depends on the action). The raw bound below only stops
 * oversized bodies before trimming.</p>
 */
public record FindingActionRequest(
        @NotNull UUID findingId,
        @NotBlank @Size(max = 20) String action,
        @Size(max = 2000) String reason
) {
}
