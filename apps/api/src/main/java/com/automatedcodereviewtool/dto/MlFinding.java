package com.automatedcodereviewtool.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * One finding returned by the ML worker. Mirrors {@code Finding} in
 * apps/ml-worker/app/schemas.py. {@code lineStart}/{@code lineEnd} are
 * new-file line numbers in {@code filePath}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlFinding(
        @JsonProperty("filePath") String filePath,
        @JsonProperty("hunkHash") String hunkHash,
        @JsonProperty("lineStart") Integer lineStart,
        @JsonProperty("lineEnd") Integer lineEnd,
        @JsonProperty("antiPattern") String antiPattern,
        @JsonProperty("category") String category,
        @JsonProperty("severity") String severity,
        @JsonProperty("confidence") BigDecimal confidence,
        @JsonProperty("explanation") String explanation
) {
}
