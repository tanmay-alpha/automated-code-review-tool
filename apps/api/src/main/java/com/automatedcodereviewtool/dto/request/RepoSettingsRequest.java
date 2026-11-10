package com.automatedcodereviewtool.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Body for {@code PUT /api/repos/{id}/settings}. A full replacement: every
 * field is required. Rule ids are checked against the taxonomy and
 * languages against the supported set by {@code RepoService}, which
 * returns 400 for unknown values.
 *
 * @param enabledRules   taxonomy ids to report; empty means every rule
 * @param minSeverity    lowest severity to report
 * @param minConfidence  findings below this confidence are dropped
 * @param languages      non-empty subset of python, javascript, typescript, java
 */
public record RepoSettingsRequest(
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 80) String> enabledRules,
        @NotNull
        @Pattern(regexp = "minor|major|critical", message = "must be one of minor, major, critical")
        String minSeverity,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal minConfidence,
        @NotNull Boolean postComments,
        @NotNull Boolean inlineComments,
        @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 20) String> languages
) {
}
