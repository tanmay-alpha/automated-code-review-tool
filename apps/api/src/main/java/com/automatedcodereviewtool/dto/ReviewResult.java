package com.automatedcodereviewtool.dto;

import com.automatedcodereviewtool.entity.Finding;
import com.automatedcodereviewtool.entity.PullRequestEntity;

import java.math.BigDecimal;
import java.util.List;

/**
 * Internal carrier returned by {@link com.automatedcodereviewtool.service.ReviewService}.
 *
 * <p>Contains the persisted {@link PullRequestEntity}, its {@link Finding}
 * list and the detector that produced them ({@code "model"} or
 * {@code "fallback"}) so the webhook flow can build a GitHub comment
 * without re-querying.</p>
 */
public record ReviewResult(
        PullRequestEntity pullRequest,
        List<Finding> findings,
        BigDecimal qualityScore,
        String engine,
        boolean success,
        boolean retryable,
        String errorMessage
) {

    /**
     * @param retryable false when retrying cannot help (the ML worker
     *                  rejected the input with a 4xx)
     */
    public static ReviewResult failed(String errorMessage, boolean retryable) {
        return new ReviewResult(null, List.of(), BigDecimal.ZERO, null, false, retryable, errorMessage);
    }

    public static ReviewResult of(PullRequestEntity pr, List<Finding> findings,
                                  BigDecimal qualityScore, String engine) {
        return new ReviewResult(pr, findings, qualityScore, engine, true, false, null);
    }
}
