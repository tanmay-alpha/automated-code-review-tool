package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.entity.Finding;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * GitHub Markdown for review results.
 *
 * <p>File paths, rule text, evidence and explanations come from pull
 * requests whose authors are untrusted on public repositories. Every such
 * value goes through {@link #code} (an inline code span: no Markdown, HTML,
 * links or mentions) or {@link #text} (all ASCII punctuation backslash-
 * escaped; mentions, issue references and URL schemes broken with a
 * zero-width space). Bodies stay under GitHub's 65,536-character limit.</p>
 */
public final class ReviewCommentFormatter {

    /** GitHub's maximum issue-comment / review body length. */
    public static final int GITHUB_MAX_BODY_CHARS = 65_536;
    /** Budget for generated bodies, leaving headroom below the hard limit. */
    static final int BODY_BUDGET_CHARS = 60_000;

    private static final String ZWSP = "\u200B";
    private static final String ASCII_PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";

    private static final int MAX_PATH = 300;
    private static final int MAX_PATTERN = 80;
    private static final int MAX_EXPLANATION = 500;
    private static final int MAX_EVIDENCE = 200;
    private static final int MAX_REMEDIATION = 300;
    private static final int MAX_CAVEAT = 300;

    /** Most severe first, then most confident: the order findings are shown and truncated in. */
    public static final Comparator<Finding> SEVERITY_ORDER = Comparator
            .comparingInt((Finding f) -> severityRank(f.getSeverity()))
            .thenComparing(f -> f.getConfidence() == null ? BigDecimal.ZERO : f.getConfidence(),
                    Comparator.reverseOrder());

    private ReviewCommentFormatter() {
    }

    /** Hidden first line identifying the one sticky summary comment of a PR. */
    public static String summaryMarker(long repoGithubId, int prNumber) {
        return "<!-- acrt:summary repo=" + repoGithubId + " pr=" + prNumber + " -->";
    }

    /** Hidden first line identifying the inline review for one PR head. */
    public static String reviewMarker(long repoGithubId, int prNumber, String headSha) {
        return "<!-- acrt:review repo=" + repoGithubId + " pr=" + prNumber
                + " head=" + hex(headSha) + " -->";
    }

    /**
     * Sticky summary comment body, starting with {@code marker}.
     *
     * @param hiddenCount findings not shown because a developer dismissed them
     *                    or marked them false positives
     */
    public static String summary(String marker, String headSha, List<Finding> findings,
                                 BigDecimal quality, String engine, int hiddenCount) {
        List<Finding> ordered = new ArrayList<>(findings == null ? List.of() : findings);
        ordered.sort(SEVERITY_ORDER);

        StringBuilder head = new StringBuilder();
        head.append(marker).append('\n');
        head.append("## 🔍 automated-code-review-tool Review\n\n");
        String emoji = quality == null ? "❓"
                : quality.compareTo(BigDecimal.valueOf(80)) >= 0 ? "✅"
                : quality.compareTo(BigDecimal.valueOf(60)) >= 0 ? "⚠️" : "❌";
        head.append("**Quality Score: ")
                .append(quality == null ? "?" : quality.toPlainString())
                .append("/100** ").append(emoji).append("\n\n");
        head.append("Reviewed commit `").append(shortSha(headSha)).append("`.\n\n");
        if (hiddenCount > 0) {
            head.append(hiddenCount)
                    .append(" finding(s) dismissed or marked false positive on the dashboard are not shown.\n\n");
        }

        StringBuilder footer = new StringBuilder();
        footer.append("\n---\n");
        footer.append("*Posted by [automated-code-review-tool](https://github.com/tanmay-alpha/automated-code-review-tool)");
        if (engine != null && !engine.isBlank()) {
            footer.append(" · detector: ").append("fallback".equals(engine) ? "rule-based" : text(engine, 60));
        }
        footer.append(" · this comment is updated in place on every push.*\n");

        if (ordered.isEmpty()) {
            return head.append("✅ No anti-patterns detected.\n").append(footer).toString();
        }

        int critical = 0, major = 0, minor = 0;
        for (Finding f : ordered) {
            switch (severity(f.getSeverity())) {
                case "critical" -> critical++;
                case "major" -> major++;
                default -> minor++;
            }
        }
        head.append("### Findings (")
                .append(critical).append(" critical · ")
                .append(major).append(" major · ")
                .append(minor).append(" minor)\n\n");
        String tableHeader = "| Severity | Pattern | File | Lines | Confidence |\n"
                + "|----------|---------|------|-------|------------|\n";
        String detailsHeader = "\n<details>\n<summary>Why each finding was reported</summary>\n\n";
        String detailsFooter = "</details>\n";
        // Reserve room for the fixed parts and the worst-case truncation note.
        int remaining = BODY_BUDGET_CHARS - head.length() - footer.length() - tableHeader.length()
                - detailsHeader.length() - detailsFooter.length() - 200;

        StringBuilder rows = new StringBuilder();
        StringBuilder details = new StringBuilder();
        int shown = 0;
        for (Finding f : ordered) {
            String row = tableRow(f);
            String detail = detail(shown + 1, f);
            if (row.length() + detail.length() > remaining) {
                break;
            }
            rows.append(row);
            details.append(detail);
            remaining -= row.length() + detail.length();
            shown++;
        }

        StringBuilder body = new StringBuilder(head).append(tableHeader).append(rows);
        if (shown < ordered.size()) {
            body.append("\n> ⚠️ ").append(ordered.size() - shown)
                    .append(" more finding(s) not shown: this comment is limited to ")
                    .append(GITHUB_MAX_BODY_CHARS)
                    .append(" characters. See the dashboard for the full review.\n");
        }
        if (shown > 0) {
            body.append(detailsHeader).append(details).append(detailsFooter);
        }
        return body.append(footer).toString();
    }

    /** Body of the inline review that carries the per-line comments. */
    public static String reviewBody(String marker, String headSha, int commentCount) {
        return marker + "\n**automated-code-review-tool**: " + commentCount
                + " finding(s) on lines added in `" + shortSha(headSha)
                + "`. The summary comment on this pull request lists every finding.\n";
    }

    /** One inline review comment. */
    public static String inlineComment(Finding f) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(severityLabel(f.getSeverity())).append("** · ")
                .append(code(f.getAntiPattern(), MAX_PATTERN, false))
                .append(" (").append(confidence(f.getConfidence())).append(" confidence)\n\n");
        appendExplanation(sb, f, "");
        return bounded(sb.toString());
    }

    // -----------------------------------------------------------------
    // Building blocks
    // -----------------------------------------------------------------

    private static String tableRow(Finding f) {
        return "| " + severityLabel(f.getSeverity())
                + " | " + code(f.getAntiPattern(), MAX_PATTERN, true)
                + " | " + code(f.getFilePath(), MAX_PATH, true)
                + " | " + lines(f)
                + " | " + confidence(f.getConfidence()) + " |\n";
    }

    private static String detail(int index, Finding f) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(index).append(". ").append(severityLabel(f.getSeverity())).append("** ")
                .append(code(f.getAntiPattern(), MAX_PATTERN, false))
                .append(" in ").append(code(f.getFilePath(), MAX_PATH, false))
                .append(" line ").append(lines(f)).append("\n\n");
        appendExplanation(sb, f, "- ");
        return sb.append('\n').toString();
    }

    private static void appendExplanation(StringBuilder sb, Finding f, String bullet) {
        if (notBlank(f.getExplanation())) {
            sb.append(bullet).append("**Why:** ").append(text(f.getExplanation(), MAX_EXPLANATION)).append('\n');
        }
        if (notBlank(f.getEvidence())) {
            sb.append(bullet).append("**Evidence:** ").append(code(f.getEvidence(), MAX_EVIDENCE, false)).append('\n');
        }
        if (notBlank(f.getRemediation())) {
            sb.append(bullet).append("**Fix:** ").append(text(f.getRemediation(), MAX_REMEDIATION)).append('\n');
        }
        if (notBlank(f.getCaveat())) {
            sb.append(bullet).append("**May not apply if:** ").append(text(f.getCaveat(), MAX_CAVEAT)).append('\n');
        }
    }

    /**
     * Untrusted value as an inline code span. Code spans render no Markdown,
     * HTML, links or mentions; the fence is longer than any backtick run in
     * the value. Inside a table cell {@code |} must still be escaped.
     */
    static String code(String value, int maxChars, boolean inTableCell) {
        if (!notBlank(value)) {
            return "-";
        }
        String s = truncate(singleLine(value), maxChars);
        // Keep untrusted text from spelling an HTML comment, e.g. a fake marker.
        s = s.replace("<!--", "<" + ZWSP + "!--");
        if (inTableCell) {
            s = s.replace("|", "\\|");
        }
        int longestRun = 0;
        int run = 0;
        for (int i = 0; i < s.length(); i++) {
            run = s.charAt(i) == '`' ? run + 1 : 0;
            longestRun = Math.max(longestRun, run);
        }
        String fence = "`".repeat(longestRun + 1);
        return fence + " " + s + " " + fence;
    }

    /** Untrusted prose with every Markdown/HTML-significant character neutralised. */
    static String text(String value, int maxChars) {
        if (!notBlank(value)) {
            return "";
        }
        String s = truncate(singleLine(value), maxChars);
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (ASCII_PUNCTUATION.indexOf(c) >= 0) {
                out.append('\\').append(c);
                // Escaping alone still renders "@user", "#12" and "https://" as
                // text GitHub links; a zero-width space breaks the pattern.
                if (c == '@' || c == '#' || c == ':') {
                    out.append(ZWSP);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String singleLine(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            sb.append(Character.isISOControl(c) || c == '\u2028' || c == '\u2029' ? ' ' : c);
        }
        return sb.toString().strip();
    }

    /** At most {@code maxChars} UTF-16 units, never splitting a surrogate pair. */
    static String truncate(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        int end = maxChars - 1;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end) + "…";
    }

    private static String bounded(String body) {
        if (body.length() <= BODY_BUDGET_CHARS) {
            return body;
        }
        return truncate(body, BODY_BUDGET_CHARS - 40) + "\n\n*(truncated)*\n";
    }

    private static String lines(Finding f) {
        Integer start = f.getLineStart();
        Integer end = f.getLineEnd();
        String first = start == null ? "?" : start.toString();
        return end == null || end.equals(start) ? first : first + "-" + end;
    }

    private static String confidence(BigDecimal confidence) {
        return confidence == null ? "?"
                : confidence.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP) + "%";
    }

    private static String severity(String severity) {
        return severity == null ? "minor" : severity.toLowerCase(Locale.ROOT);
    }

    private static int severityRank(String severity) {
        return switch (severity(severity)) {
            case "critical" -> 0;
            case "major" -> 1;
            default -> 2;
        };
    }

    private static String severityLabel(String severity) {
        return switch (severity(severity)) {
            case "critical" -> "🔴 Critical";
            case "major" -> "🟠 Major";
            default -> "🟡 Minor";
        };
    }

    private static String shortSha(String sha) {
        String hex = hex(sha);
        return hex.length() > 12 ? hex.substring(0, 12) : hex;
    }

    /** Only hex digits survive, so a SHA can never break out of a marker or code span. */
    private static String hex(String sha) {
        if (sha == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(sha.length());
        for (int i = 0; i < sha.length() && sb.length() < 64; i++) {
            char c = Character.toLowerCase(sha.charAt(i));
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
