package com.automatedcodereviewtool.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response from {@code POST /ml/review} on the ML worker.
 *
 * <p>Extended in Part 6 with {@code engine}, {@code modelVersion} and
 * {@code taxonomyVersion} so the API layer can surface the model
 * identity without coupling to internal paths.</p>
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
