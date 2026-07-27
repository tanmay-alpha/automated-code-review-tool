package com.codelens.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Daily quality-score series for a single repository, plus the
 * most-frequent anti-pattern found in that window (if any).
 *
 * <p>Used by the Next.js dashboard's line chart (issue #18) — the
 * client renders one point per day, and the {@code topAntiPattern}
 * field drives the "biggest issue this week" callout.</p>
 */
public record QualityTrendResponse(
        UUID repoId,
        String fullName,
        List<Point> trend,
        String topAntiPattern
) {
    public record Point(
            LocalDate date,
            BigDecimal avgQuality,
            int prsReviewed,
            int criticalCount,
            int majorCount,
            int minorCount
    ) {
    }
}
