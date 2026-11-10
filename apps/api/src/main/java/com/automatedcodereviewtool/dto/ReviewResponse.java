package com.automatedcodereviewtool.dto;

import com.automatedcodereviewtool.entity.Finding;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * GET /api/reviews/{prId} response.
 */
public record ReviewResponse(
        UUID id,
        Integer githubPrNumber,
        String title,
        String authorGithub,
        String status,
        BigDecimal qualityScore,
        Instant createdAt,
        Instant reviewedAt,
        String errorMessage,
        String headSha,
        String githubPrUrl,

        // Nested repo info
        UUID repoId,
        String repoFullName,
        String repoOwnerLogin,
        String repoOwnerAvatar,

        // Findings list
        List<FindingDto> findings
) {

    /**
     * One finding. {@code status} is the triage state: {@code open}
     * (untouched or reopened), {@code accepted}, {@code dismissed},
     * {@code fixed} or {@code false_positive} — the values allowed by the
     * {@code findings.status} CHECK constraint and written by
     * {@code POST /api/scan/action}, together with {@code triageReason}
     * and {@code triagedAt}.
     */
    public record FindingDto(
            UUID id,
            String filePath,
            Integer lineStart,
            Integer lineEnd,
            String antiPattern,
            String category,
            String severity,
            BigDecimal confidence,
            String explanation,
            String codeSnippet,
            String status,
            String triageReason,
            Instant triagedAt
    ) {
    }
}