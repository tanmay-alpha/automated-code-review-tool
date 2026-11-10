package com.automatedcodereviewtool.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/metrics/finding-summary?repo=…&days=…}: triage outcome per
 * rule and finding volume per detector engine for findings created in the
 * window. A high {@code falsePositive}/{@code dismissed} share for a rule is
 * the signal to disable it in the repository settings.
 */
public record FindingSummaryResponse(
        UUID repoId,
        int days,
        List<RuleSummary> byRule,
        List<EngineSummary> byEngine
) {
    public record RuleSummary(
            String antiPattern,
            long total,
            long open,
            long accepted,
            long dismissed,
            long fixed,
            long falsePositive
    ) {
    }

    /** {@code engine} is {@code "unknown"} for findings stored without one. */
    public record EngineSummary(String engine, long total) {
    }
}
