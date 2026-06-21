package com.codelens.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response from {@code POST /ml/review} on the ML worker.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlReviewResponse(
        @JsonProperty("findings") List<MlFinding> findings,
        @JsonProperty("qualityScore") BigDecimal qualityScore,
        @JsonProperty("processingTimeMs") Long processingTimeMs
) {
}
