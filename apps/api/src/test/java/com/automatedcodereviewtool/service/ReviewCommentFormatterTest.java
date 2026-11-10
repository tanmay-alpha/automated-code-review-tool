package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.entity.Finding;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewCommentFormatterTest {

    private static final String MARKER = ReviewCommentFormatter.summaryMarker(123L, 7);
    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void markersAreStableAndHeadOnlyKeepsHexDigits() {
        assertThat(MARKER).isEqualTo("<!-- acrt:summary repo=123 pr=7 -->");
        assertThat(ReviewCommentFormatter.reviewMarker(123L, 7, "abc --> <b>x</b>"))
                .isEqualTo("<!-- acrt:review repo=123 pr=7 head=abcbb -->");
    }

    @Test
    void summaryStartsWithTheMarkerAndShowsTheReviewedHead() {
        String body = ReviewCommentFormatter.summary(MARKER, HEAD, List.of(), new BigDecimal("100.00"), "fallback", 0);

        assertThat(body).startsWith(MARKER + "\n");
        assertThat(body).contains("`0123456789ab`", "No anti-patterns detected", "detector: rule-based");
    }

    @Test
    void untrustedPathCannotBreakTheTableOrInjectMarkdown() {
        String path = "src/`x`|evil @admin <img src=x onerror=alert(1)> [click](https://evil.example) #1.py";
        Finding f = finding(path, "critical");

        String body = ReviewCommentFormatter.summary(MARKER, HEAD, List.of(f), BigDecimal.TEN, "fallback", 0);

        String row = body.lines().filter(l -> l.startsWith("| 🔴")).findFirst().orElseThrow();
        // Exactly the five columns: the pipe in the path is escaped inside the code span.
        assertThat(row.split("(?<!\\\\)\\|", -1)).hasSize(7);
        // The path sits in a code span fenced by more backticks than it contains.
        assertThat(row).contains("`` src/`x`\\|evil @admin <img src=x onerror=alert(1)> "
                + "[click](https://evil.example) #1.py ``");
    }

    @Test
    void untrustedProseIsEscapedAndCannotMentionOrLink() {
        Finding f = finding("a.py", "major");
        f.setExplanation("Ping @maintainers, see #12 and https://evil.example <script>x</script> **bold** [l](u)");
        f.setRemediation("line1\nline2 | `tick`");
        f.setCaveat("_under_ ~strike~");

        String body = ReviewCommentFormatter.summary(MARKER, HEAD, List.of(f), BigDecimal.TEN, "model", 0);

        assertThat(body).contains("\\@\u200Bmaintainers", "\\#\u200B12", "https\\:\u200B\\/\\/evil\\.example",
                "\\<script\\>", "\\*\\*bold\\*\\*", "\\[l\\]\\(u\\)", "line1 line2 \\| \\`tick\\`",
                "\\_under\\_ \\~strike\\~");
        assertThat(body).doesNotContain("@maintainers", "<script>", "](u)");
    }

    @Test
    void untrustedTextCannotForgeTheMarker() {
        Finding f = finding("<!-- acrt:summary repo=1 pr=1 -->", "minor");
        f.setEvidence("<!-- acrt:summary repo=1 pr=1 -->");

        String body = ReviewCommentFormatter.summary(MARKER, HEAD, List.of(f), BigDecimal.TEN, "fallback", 0);

        assertThat(body.indexOf("<!--")).isZero();
        assertThat(body.indexOf("<!--", 1)).isEqualTo(-1);
    }

    @Test
    void explainabilityFieldsAreRendered() {
        Finding f = finding("src/a.py", "critical");
        f.setExplanation("hard-coded key");
        f.setEvidence("API_KEY = \"<REDACTED_SECRET>\"");
        f.setRemediation("Read it from the environment");
        f.setCaveat("Test fixtures");

        String body = ReviewCommentFormatter.summary(MARKER, HEAD, List.of(f), BigDecimal.TEN, "fallback", 0);

        assertThat(body).contains("**Why:** hard\\-coded key",
                "**Evidence:** ` API_KEY = \"<REDACTED_SECRET>\" `",
                "**Fix:** Read it from the environment",
                "**May not apply if:** Test fixtures");
        String inline = ReviewCommentFormatter.inlineComment(f);
        assertThat(inline).contains("🔴 Critical", "Read it from the environment");
    }

    @Test
    void bodyStaysWithinGitHubsLimitWithATruncationNote() {
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            Finding f = finding("src/" + "d".repeat(200) + i + ".py", i % 3 == 0 ? "critical" : "minor");
            f.setExplanation("e".repeat(500));
            f.setRemediation("r".repeat(300));
            f.setCaveat("c".repeat(300));
            f.setEvidence("v".repeat(200));
            findings.add(f);
        }

        String body = ReviewCommentFormatter.summary(MARKER, HEAD, findings, BigDecimal.ZERO, "fallback", 0);

        assertThat(body.length()).isLessThanOrEqualTo(ReviewCommentFormatter.GITHUB_MAX_BODY_CHARS);
        assertThat(body).contains("more finding(s) not shown").endsWith("in place on every push.*\n");
        // Most severe findings are kept first: the budget is spent on criticals.
        assertThat(body).contains("🔴 Critical").doesNotContain("🟡 Minor");
    }

    @Test
    void truncateNeverSplitsASurrogatePair() {
        String s = "ab\uD83D\uDE00cd";
        assertThat(ReviewCommentFormatter.truncate(s, 4)).isEqualTo("ab…");
    }

    private static Finding finding(String path, String severity) {
        return Finding.builder()
                .id(UUID.randomUUID())
                .filePath(path)
                .lineStart(2)
                .lineEnd(2)
                .antiPattern("SECURITY_HARDCODED_SECRET")
                .category("SECURITY")
                .severity(severity)
                .confidence(new BigDecimal("0.9"))
                .build();
    }
}
