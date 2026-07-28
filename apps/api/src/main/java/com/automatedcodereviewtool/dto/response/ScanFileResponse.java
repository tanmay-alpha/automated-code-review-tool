package com.automatedcodereviewtool.dto.response;

import com.automatedcodereviewtool.dto.MlFinding;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response body for {@code POST /api/scan/file}.
 *
 * <p>Mirrors the shape of {@link com.automatedcodereviewtool.dto.MlReviewResponse}
 * but lives in the response sub-package because it represents a
 * single-file scan (no PR/Repo context).</p>
 */
public record ScanFileResponse(
        List<MlFinding> findings,
        BigDecimal qualityScore,
        String language
) {
}