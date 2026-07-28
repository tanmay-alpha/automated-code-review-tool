package com.automatedcodereviewtool.dto;

import java.math.BigDecimal;

/**
 * Aggregated finding count grouped by anti-pattern and severity.
 *
 * <p>Returned by {@link com.automatedcodereviewtool.repository.FindingRepository}
 * via a JPQL projection so the service never iterates raw {@code Finding}
 * entities.</p>
 */
public record FindingStats(
        String antiPattern,
        String severity,
        long count
) {

    /**
     * Returns the finding rate as a BigDecimal (count / total * 100).
     */
    public BigDecimal rateOf(long total) {
        if (total == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(count)
                .divide(BigDecimal.valueOf(total), 4, BigDecimal.ROUND_HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }
}
