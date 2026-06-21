package com.codelens.dto;

import com.codelens.entity.Finding;
import com.codelens.entity.PullRequestEntity;

import java.math.BigDecimal;
import java.util.List;

/**
 * Internal carrier returned by {@link com.codelens.service.ReviewService}.
 *
 * <p>Contains the persisted {@link PullRequestEntity} and its
 * {@link Finding} list so the orchestrating caller (the webhook
 * flow) can build a GitHub comment without re-querying.</p>
 */
public record ReviewResult(
        PullRequestEntity pullRequest,
        List<Finding> findings,
        BigDecimal qualityScore,
        boolean success,
        String errorMessage
) {

    public static ReviewResult empty(String errorMessage) {
        return new ReviewResult(null, List.of(), BigDecimal.ZERO, false, errorMessage);
    }

    public static ReviewResult of(PullRequestEntity pr, List<Finding> findings, BigDecimal qualityScore) {
        return new ReviewResult(pr, findings, qualityScore, true, null);
    }
}