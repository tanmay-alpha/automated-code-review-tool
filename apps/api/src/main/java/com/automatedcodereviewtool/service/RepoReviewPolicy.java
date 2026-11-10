package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.dto.MlFinding;
import com.automatedcodereviewtool.entity.AntiPattern;
import com.automatedcodereviewtool.entity.Repository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Applies a repository's review settings (V20, {@code PUT /api/repos/{id}/settings})
 * to the findings returned by the ML worker. Pure: reads only the given
 * {@link Repository}'s settings fields and the in-memory taxonomy snapshot;
 * never touches the database or the network.
 *
 * <p>A finding is kept only when all of these hold:</p>
 * <ul>
 *   <li><b>rule</b> — the enabled-rule list is empty, or contains the finding's {@code antiPattern};</li>
 *   <li><b>severity</b> — at least the minimum severity. The canonical taxonomy
 *       severity is used when the rule is known (that is what ReviewService
 *       persists); otherwise the worker's value; an unrecognised severity
 *       counts as {@code minor};</li>
 *   <li><b>confidence</b> — at least the minimum confidence (missing = 0);</li>
 *   <li><b>language</b> — the file's language (from its extension) is enabled.
 *       Files whose extension maps to none of the supported languages are
 *       kept: the setting restricts the supported languages only.</li>
 * </ul>
 */
@Component
public class RepoReviewPolicy {

    private static final Map<String, Integer> SEVERITY_RANK = Map.of(
            "minor", 0,
            "major", 1,
            "critical", 2);

    private static final Map<String, String> LANGUAGE_BY_EXTENSION = Map.ofEntries(
            Map.entry("py", "python"),
            Map.entry("pyi", "python"),
            Map.entry("js", "javascript"),
            Map.entry("jsx", "javascript"),
            Map.entry("mjs", "javascript"),
            Map.entry("cjs", "javascript"),
            Map.entry("ts", "typescript"),
            Map.entry("tsx", "typescript"),
            Map.entry("mts", "typescript"),
            Map.entry("cts", "typescript"),
            Map.entry("java", "java"));

    private final TaxonomyService taxonomyService;

    public RepoReviewPolicy(TaxonomyService taxonomyService) {
        this.taxonomyService = taxonomyService;
    }

    /** The findings the repository's settings allow, in their original order. */
    public List<MlFinding> apply(Repository repo, List<MlFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return List.of();
        }
        List<String> enabledRules = repo.getReviewEnabledRules();
        int minSeverity = rank(repo.getReviewMinSeverity());
        BigDecimal minConfidence = repo.getReviewMinConfidence() == null
                ? BigDecimal.ZERO : repo.getReviewMinConfidence();
        List<String> languages = repo.getReviewLanguages();

        return findings.stream()
                .filter(Objects::nonNull)
                .filter(f -> enabledRules == null || enabledRules.isEmpty()
                        || enabledRules.contains(f.antiPattern()))
                .filter(f -> rank(severityOf(f)) >= minSeverity)
                .filter(f -> (f.confidence() == null ? BigDecimal.ZERO : f.confidence())
                        .compareTo(minConfidence) >= 0)
                .filter(f -> {
                    String language = languageOf(f.filePath());
                    return language == null || languages == null || languages.contains(language);
                })
                .toList();
    }

    /** Whether the review summary comment is posted to the pull request. */
    public boolean postComments(Repository repo) {
        return repo.isReviewPostComments();
    }

    /** Whether findings are also posted as inline review comments. */
    public boolean inlineComments(Repository repo) {
        return repo.isReviewInlineComments();
    }

    private String severityOf(MlFinding finding) {
        return taxonomyService.find(finding.antiPattern())
                .map(AntiPattern::getDefaultSeverity)
                .orElse(finding.severity());
    }

    private static int rank(String severity) {
        if (severity == null) return 0;
        return SEVERITY_RANK.getOrDefault(severity.toLowerCase(Locale.ROOT), 0);
    }

    /** Supported language for a path, or null when the extension is not one of them. */
    static String languageOf(String filePath) {
        if (filePath == null) return null;
        int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        int dot = filePath.lastIndexOf('.');
        if (dot <= slash + 1 || dot == filePath.length() - 1) return null;
        return LANGUAGE_BY_EXTENSION.get(filePath.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
