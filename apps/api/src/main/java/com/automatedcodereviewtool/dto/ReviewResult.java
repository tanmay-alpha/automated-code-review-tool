package com.automatedcodereviewtool.dto;

import com.automatedcodereviewtool.entity.Finding;
import com.automatedcodereviewtool.entity.PullRequestEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Outcome of {@link com.automatedcodereviewtool.service.ReviewService#orchestrateReview}.
 *
 * <p>{@code REVIEWED} carries the persisted findings and the id of the
 * publication queued in the same transaction. {@code SUPERSEDED} means the
 * PR head moved on while the worker was analysing, and {@code LEASE_LOST}
 * that another attempt now owns the delivery; neither wrote anything.</p>
 */
public record ReviewResult(
        Outcome outcome,
        PullRequestEntity pullRequest,
        List<Finding> findings,
        BigDecimal qualityScore,
        String engine,
        boolean retryable,
        String errorMessage,
        UUID publicationId
) {

    public enum Outcome { REVIEWED, FAILED, SUPERSEDED, LEASE_LOST }

    public boolean success() {
        return outcome == Outcome.REVIEWED;
    }

    /**
     * @param retryable false when retrying cannot help (the ML worker
     *                  rejected the input with a 4xx, or the diff is too large)
     */
    public static ReviewResult failed(String errorMessage, boolean retryable) {
        return new ReviewResult(Outcome.FAILED, null, List.of(), BigDecimal.ZERO, null,
                retryable, errorMessage, null);
    }

    public static ReviewResult superseded(String message) {
        return new ReviewResult(Outcome.SUPERSEDED, null, List.of(), null, null, false, message, null);
    }

    public static ReviewResult leaseLost() {
        return new ReviewResult(Outcome.LEASE_LOST, null, List.of(), null, null, false,
                "delivery lease lost to a newer attempt", null);
    }

    public static ReviewResult of(PullRequestEntity pr, List<Finding> findings,
                                  BigDecimal qualityScore, String engine, UUID publicationId) {
        return new ReviewResult(Outcome.REVIEWED, pr, findings, qualityScore, engine, false, null, publicationId);
    }
}
