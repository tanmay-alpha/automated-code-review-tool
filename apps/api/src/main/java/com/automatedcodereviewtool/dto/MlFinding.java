package com.automatedcodereviewtool.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * One finding returned by the ML worker. Mirrors {@code Finding} in
 * apps/ml-worker/app/schemas.py. {@code lineStart}/{@code lineEnd} are
 * new-file line numbers in {@code filePath}.
 *
 * <p>{@code evidence} (the triggering added line, ≤200 chars),
 * {@code remediation} and {@code caveat} (≤300 chars each) explain the
 * finding. They are optional on the wire; the API redacts and bounds them
 * again before storing.</p>
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
        @JsonProperty("explanation") String explanation,
        @JsonProperty("evidence") String evidence,
        @JsonProperty("remediation") String remediation,
        @JsonProperty("caveat") String caveat
) {

    /** A finding without the optional explainability fields. */
    public MlFinding(String filePath, String hunkHash, Integer lineStart, Integer lineEnd,
                     String antiPattern, String category, String severity,
                     BigDecimal confidence, String explanation) {
        this(filePath, hunkHash, lineStart, lineEnd, antiPattern, category, severity,
                confidence, explanation, null, null, null);
    }
}
