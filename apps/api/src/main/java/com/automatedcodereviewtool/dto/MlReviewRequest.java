package com.automatedcodereviewtool.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request body for {@code POST /ml/review} on the ML worker.
 *
 * <p>{@code mode} is "diff" when sending a unified diff (PR review
 * path) or "file" when sending a single file's contents (VS Code
 * extension / GitHub Action path).</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MlReviewRequest(
        String diff,
        String language,
        String mode,
        String filePath
) {
    public static MlReviewRequest diff(String diff, String language) {
        return new MlReviewRequest(diff, language, "diff", null);
    }

    /** File-mode request; {@code filePath} is optional and lets the worker apply extension-specific rules. */
    public static MlReviewRequest file(String content, String language, String filePath) {
        return new MlReviewRequest(content, language, "file", filePath);
    }
}
