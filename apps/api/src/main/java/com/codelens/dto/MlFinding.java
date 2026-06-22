package com.codelens.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * One finding returned by the ML worker. Mirrors the Pydantic model in
 * the worker's {@code ReviewResponse.findings} list.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlFinding(
        @JsonProperty("lineStart") Integer lineStart,
        @JsonProperty("lineEnd") Integer lineEnd,
        @JsonProperty("antiPattern") String antiPattern,
        @JsonProperty("category") String category,
        @JsonProperty("severity") String severity,
        @JsonProperty("confidence") BigDecimal confidence,
        @JsonProperty("explanation") String explanation
) {
}
