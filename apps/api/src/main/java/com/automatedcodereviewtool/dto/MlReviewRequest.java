package com.automatedcodereviewtool.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request body for {@code POST /ml/review} on the ML worker.
 *
 * <p>{@code mode} is "diff" when sending a unified diff (PR review
 * path) or "file" when sending a single file's contents (VS Code
 * extension path).</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MlReviewRequest(
        String diff,
        String language,
        String mode,
        String filePath
) {
    /** Compatibility constructor for callers that do not provide a file path. */
    public MlReviewRequest(String diff, String language, String mode) {
        this(diff, language, mode, null);
    }

    /** Convenience factory for the diff mode (PR review). */
    public static MlReviewRequest diff(String diff, String language) {
        return new MlReviewRequest(diff, language, "diff", null);
    }

    /** Convenience factory for the file mode (VS Code extension). */
    public static MlReviewRequest file(String content, String language) {
        return file(content, language, null);
    }

    /** File-mode request with an optional path for extension-aware fallback rules. */
    public static MlReviewRequest file(String content, String language, String filePath) {
        return new MlReviewRequest(content, language, "file", filePath);
    }
}
