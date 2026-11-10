package com.automatedcodereviewtool.service;

/**
 * One claimed attempt at reviewing a webhook delivery.
 *
 * @param deliveryId     the delivery being processed
 * @param attempt        fencing token from the claim; review writes commit only while it still holds
 * @param headSha        the PR head this delivery reviews
 * @param postComments   publish a sticky summary comment (otherwise the publication is {@code skipped})
 * @param inlineComments also publish one inline review on added lines (requires {@code postComments})
 */
public record ReviewRun(String deliveryId, int attempt, String headSha,
                        boolean postComments, boolean inlineComments) {
}
