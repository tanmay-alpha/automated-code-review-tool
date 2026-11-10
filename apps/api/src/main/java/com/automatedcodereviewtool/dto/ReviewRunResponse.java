package com.automatedcodereviewtool.dto;

import java.time.Instant;

/**
 * One webhook-driven review run of a PR ({@code GET /api/reviews/{prId}/runs}).
 *
 * @param status      delivery state: received | processing | completed | failed | dead_letter | superseded
 * @param lastError   explainable, secret-redacted reason (e.g. {@code github_auth_revoked: …})
 * @param publication GitHub publication of this run, or {@code null} when the run produced no review
 */
public record ReviewRunResponse(
        String deliveryId,
        String headSha,
        String action,
        String status,
        int attemptCount,
        String lastError,
        Instant receivedAt,
        Instant completedAt,
        Publication publication
) {

    /**
     * @param status pending | published | failed | superseded | skipped
     */
    public record Publication(String status, String commentUrl, String publishedHeadSha) {
    }
}
