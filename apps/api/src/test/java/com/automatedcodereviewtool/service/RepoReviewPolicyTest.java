package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.dto.MlFinding;
import com.automatedcodereviewtool.entity.AntiPattern;
import com.automatedcodereviewtool.entity.Repository;
import com.automatedcodereviewtool.repository.AntiPatternRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pure unit tests for {@link RepoReviewPolicy}: no Spring context, no database. */
class RepoReviewPolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RepoReviewPolicy policy = new RepoReviewPolicy(taxonomy());

    private static TaxonomyService taxonomy() {
        AntiPatternRepository repository = mock(AntiPatternRepository.class);
        OffsetDateTime now = OffsetDateTime.now();
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(
                new AntiPattern("READABILITY_MAGIC_NUMBER", "Magic Number", "READABILITY", "minor", null, true, now),
                new AntiPattern("SECURITY_HARDCODED_SECRET", "Hardcoded Secret", "SECURITY", "critical", null, true, now),
                new AntiPattern("SECURITY_WEAK_CRYPTO", "Weak Cryptography", "SECURITY", "major", null, true, now)));
        TaxonomyService service = new TaxonomyService(repository);
        service.reload();
        return service;
    }

    /** Built from JSON like the worker response, so new optional fields do not break the test. */
    private static MlFinding finding(String antiPattern, String severity, String confidence, String filePath) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("antiPattern", antiPattern);
        body.put("severity", severity);
        body.put("confidence", confidence == null ? null : new BigDecimal(confidence));
        body.put("filePath", filePath);
        body.put("lineStart", 1);
        body.put("lineEnd", 1);
        return JSON.convertValue(body, MlFinding.class);
    }

    private static Repository repo() {
        return Repository.builder().fullName("o/r").build();
    }

    @Test
    void defaultsKeepEveryFinding() {
        List<MlFinding> findings = List.of(
                finding("READABILITY_MAGIC_NUMBER", "minor", "0.10", "a.py"),
                finding("SECURITY_WEAK_CRYPTO", "major", "0.90", "b.ts"),
                finding("UNKNOWN_RULE", "weird", null, "c.go"),
                finding("SECURITY_HARDCODED_SECRET", "critical", "0.50", null));

        Repository repo = repo();
        assertThat(policy.apply(repo, findings)).containsExactlyElementsOf(findings);
        assertThat(policy.postComments(repo)).isTrue();
        assertThat(policy.inlineComments(repo)).isFalse();
    }

    @Test
    void enabledRulesRestrictToTheListedIds() {
        Repository repo = repo();
        repo.setReviewEnabledRules(List.of("SECURITY_WEAK_CRYPTO"));
        MlFinding crypto = finding("SECURITY_WEAK_CRYPTO", "major", "0.9", "a.py");

        assertThat(policy.apply(repo, List.of(
                finding("READABILITY_MAGIC_NUMBER", "minor", "0.9", "a.py"),
                crypto,
                finding("UNKNOWN_RULE", "major", "0.9", "a.py"))))
                .containsExactly(crypto);
    }

    @Test
    void minSeverityUsesTheTaxonomySeverityForKnownRules() {
        Repository repo = repo();
        repo.setReviewMinSeverity("major");
        // The worker's severity is ignored for known rules: ReviewService persists the
        // taxonomy severity (weak crypto = major, magic number = minor).
        MlFinding understated = finding("SECURITY_WEAK_CRYPTO", "minor", "0.9", "a.py");
        MlFinding secret = finding("SECURITY_HARDCODED_SECRET", "minor", "0.9", "a.py");
        MlFinding unknownMajor = finding("UNKNOWN_RULE", "MAJOR", "0.9", "a.py");

        assertThat(policy.apply(repo, List.of(
                finding("READABILITY_MAGIC_NUMBER", "critical", "0.9", "a.py"),
                understated, secret, unknownMajor,
                finding("UNKNOWN_RULE", "bogus", "0.9", "a.py"))))
                .containsExactly(understated, secret, unknownMajor);

        repo.setReviewMinSeverity("critical");
        assertThat(policy.apply(repo, List.of(understated, secret))).containsExactly(secret);
    }

    @Test
    void minConfidenceIsInclusiveAndMissingConfidenceIsZero() {
        Repository repo = repo();
        repo.setReviewMinConfidence(new BigDecimal("0.700"));
        MlFinding atThreshold = finding("SECURITY_WEAK_CRYPTO", "major", "0.7", "a.py");
        MlFinding above = finding("SECURITY_WEAK_CRYPTO", "major", "0.95", "a.py");

        assertThat(policy.apply(repo, List.of(
                finding("SECURITY_WEAK_CRYPTO", "major", "0.699", "a.py"),
                atThreshold, above,
                finding("SECURITY_WEAK_CRYPTO", "major", null, "a.py"))))
                .containsExactly(atThreshold, above);
    }

    @Test
    void languagesFilterByExtensionAndKeepUnsupportedFiles() {
        Repository repo = repo();
        repo.setReviewLanguages(List.of("python", "java"));
        MlFinding py = finding("SECURITY_WEAK_CRYPTO", "major", "0.9", "src/app/Main.PY");
        MlFinding java = finding("SECURITY_WEAK_CRYPTO", "major", "0.9", "src/Main.java");
        MlFinding go = finding("SECURITY_WEAK_CRYPTO", "major", "0.9", "cmd/main.go");
        MlFinding noExtension = finding("SECURITY_WEAK_CRYPTO", "major", "0.9", "Dockerfile");

        assertThat(policy.apply(repo, List.of(
                py, java, go, noExtension,
                finding("SECURITY_WEAK_CRYPTO", "major", "0.9", "web/app.tsx"),
                finding("SECURITY_WEAK_CRYPTO", "major", "0.9", "web/util.mjs"))))
                .containsExactly(py, java, go, noExtension);
    }

    @Test
    void commentFlagsComeFromTheRepository() {
        Repository repo = repo();
        repo.setReviewPostComments(false);
        repo.setReviewInlineComments(true);
        assertThat(policy.postComments(repo)).isFalse();
        assertThat(policy.inlineComments(repo)).isTrue();
    }

    @Test
    void nullOrEmptyInputYieldsEmptyList() {
        assertThat(policy.apply(repo(), null)).isEmpty();
        assertThat(policy.apply(repo(), List.of())).isEmpty();
    }

    @Test
    void languageOfMapsSupportedExtensionsOnly() {
        assertThat(RepoReviewPolicy.languageOf("a/b/c.py")).isEqualTo("python");
        assertThat(RepoReviewPolicy.languageOf("a\\b\\C.Java")).isEqualTo("java");
        assertThat(RepoReviewPolicy.languageOf("x.cts")).isEqualTo("typescript");
        assertThat(RepoReviewPolicy.languageOf("x.jsx")).isEqualTo("javascript");
        assertThat(RepoReviewPolicy.languageOf(".py")).isNull();
        assertThat(RepoReviewPolicy.languageOf("dir.py/Makefile")).isNull();
        assertThat(RepoReviewPolicy.languageOf("trailing.")).isNull();
        assertThat(RepoReviewPolicy.languageOf(null)).isNull();
    }
}
