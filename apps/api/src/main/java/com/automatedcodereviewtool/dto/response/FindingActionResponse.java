package com.automatedcodereviewtool.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body for {@code POST /api/scan/action}: the finding's triage
 * state after the action, so the UI can update without a refetch.
 *
 * @param status    stored status: open, accepted, dismissed, fixed or false_positive
 * @param reason    trimmed reason recorded with this action (null when none was given)
 * @param triagedBy GitHub login of the user who performed the action
 */
public record FindingActionResponse(
        UUID findingId,
        String status,
        String reason,
        Instant triagedAt,
        String triagedBy
) {
}
