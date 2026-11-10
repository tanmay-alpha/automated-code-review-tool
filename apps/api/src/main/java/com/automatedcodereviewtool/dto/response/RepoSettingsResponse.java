package com.automatedcodereviewtool.dto.response;

import com.automatedcodereviewtool.entity.Repository;

import java.math.BigDecimal;
import java.util.List;

/** {@code GET/PUT /api/repos/{id}/settings} response: the repository's review policy. */
public record RepoSettingsResponse(
        List<String> enabledRules,
        String minSeverity,
        BigDecimal minConfidence,
        boolean postComments,
        boolean inlineComments,
        List<String> languages
) {
    public static RepoSettingsResponse from(Repository repo) {
        return new RepoSettingsResponse(
                repo.getReviewEnabledRules(),
                repo.getReviewMinSeverity(),
                repo.getReviewMinConfidence(),
                repo.isReviewPostComments(),
                repo.isReviewInlineComments(),
                repo.getReviewLanguages());
    }
}
