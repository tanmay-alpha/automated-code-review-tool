package com.automatedcodereviewtool.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response from {@code POST /ml/review} on the ML worker. Mirrors
 * {@code ReviewResponse} in apps/ml-worker/app/schemas.py; {@code engine}
 * is {@code "model"} or {@code "fallback"} (rule-based).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlReviewResponse(
        @JsonProperty("findings") List<MlFinding> findings,
        @JsonProperty("qualityScore") BigDecimal qualityScore,
        @JsonProperty("processingTimeMs") int processingTimeMs,
        @JsonProperty("windowsProcessed") int windowsProcessed,
        @JsonProperty("engine") String engine,
        @JsonProperty("modelVersion") String modelVersion,
        @JsonProperty("taxonomyVersion") String taxonomyVersion
) {
}
