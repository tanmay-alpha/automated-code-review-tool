package com.automatedcodereviewtool.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /api/scan/file}: one file's source text, its
 * language, and optionally its path (lets the worker apply
 * extension-specific rules). {@code language} must be one of the values
 * the ML worker accepts, so bad input is a 400 here rather than a 422
 * from the worker.
 */
public record ScanFileRequest(
        @NotBlank @Size(max = 200_000) String content,
        @NotBlank
        @Pattern(regexp = "python|javascript|java|unknown",
                message = "must be one of python, javascript, java, unknown")
        String language,
        @Size(max = 1024) String filePath
) {
}
