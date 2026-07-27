package com.codelens.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /api/scan/file} — paste-source or upload-by-path
 * file review.
 *
 * <p>{@code content} is the actual file source (or its file-path
 * resolved to disk on the caller's side). {@code language} is the
 * declared language (used as a hint for the model).</p>
 */
public record ScanFileRequest(
        @NotBlank @Size(max = 200_000) String content,
        @NotBlank @Size(max = 50) String language,
        @Size(max = 1024) String filePath
) {
}