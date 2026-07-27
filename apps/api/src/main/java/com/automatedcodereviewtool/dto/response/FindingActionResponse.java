package com.codelens.dto.response;

import java.util.UUID;

/**
 * Response body for {@code POST /api/scan/action}.
 *
 * <p>Confirms the action was recorded. The finding itself is updated
 * by the controller (status + audit timestamp) — we just echo the ID
 * and the applied action so the UI can update without a refetch.</p>
 */
public record FindingActionResponse(
        UUID findingId,
        String action,
        String status
) {
}