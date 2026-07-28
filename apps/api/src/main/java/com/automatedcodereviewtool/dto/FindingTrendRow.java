package com.automatedcodereviewtool.dto;

import java.math.BigDecimal;

/**
 * Daily trend row: finding count per day, optionally broken down by severity.
 *
 * <p>Returned by {@link com.automatedcodereviewtool.repository.FindingRepository}
 * via a JPQL projection.</p>
 */
public record FindingTrendRow(
        java.time.LocalDate date,
        long count,
        String severity
) {

    /**
     * Returns the daily rate as a BigDecimal (count / totalDays * 100).
     */
    public BigDecimal dailyRate(long totalDays) {
        if (totalDays == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(count)
                .divide(BigDecimal.valueOf(totalDays), 4, BigDecimal.ROUND_HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }
}
