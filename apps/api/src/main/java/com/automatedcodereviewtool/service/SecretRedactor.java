package com.automatedcodereviewtool.service;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heuristic secret redactor.
 *
 * <p>Replaces literal-looking secrets (hardcoded keys, tokens,
 * passwords) with {@code "<REDACTED_SECRET>"} while preserving
 * enough structural information for downstream detection. Operates
 * before persistence so the canonical {@code ml.code_samples}
 * table never holds a real credential.</p>
 *
 * <p>Patterns covered (case-insensitive):</p>
 * <ul>
 *   <li>{@code password = "..."}, {@code api_key = "..."}, {@code token = "..."}, etc.</li>
 *   <li>{@code AWS_SECRET_ACCESS_KEY=...} (env-style)</li>
 *   <li>High-entropy quoted strings of 32+ characters (likely API keys)</li>
 * </ul>
 *
 * <p>Versioned via {@link #version()} so persisted rows can record
 * which redaction rules applied.</p>
 */
@Component
public class SecretRedactor {

    /** Bump whenever the patterns below change. */
    public static final String CURRENT_VERSION = "v1";

    /** Common assignment patterns: `name = "value"` or `name: "value"`. */
    private static final Pattern ASSIGNMENT_PATTERN = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|api[_-]?key|access[_-]?key|" +
                    "auth[_-]?token|bearer|client[_-]?secret|private[_-]?key|" +
                    "ssh[_-]?key|jwt|aws[_-]?secret[_-]?access[_-]?key)\\s*[:=]\\s*[\"']" +
                    "([^\"']{4,})[\"']");

    /** Bare high-entropy quoted strings, 32+ chars, no whitespace. */
    private static final Pattern HIGH_ENTROPY = Pattern.compile(
            "[\"']([A-Za-z0-9+/=_-]{32,})[\"']");

    private static final String REDACTION_PLACEHOLDER = "<REDACTED_SECRET>";

    /**
     * @param input raw text (diff, code, etc.)
     * @return redacted copy, safe to persist
     */
    public String redact(String input) {
        if (input == null || input.isEmpty()) return input;
        String step1 = ASSIGNMENT_PATTERN.matcher(input).replaceAll(
                Matcher.quoteReplacement("$1") + " = " + Matcher.quoteReplacement("\"" + REDACTION_PLACEHOLDER + "\""));
        return HIGH_ENTROPY.matcher(step1).replaceAll(
                Matcher.quoteReplacement("\"" + REDACTION_PLACEHOLDER + "\""));
    }

    /**
     * Redaction rules version. Persisted on every code sample so the
     * pipeline can be reproduced.
     */
    public String version() {
        return CURRENT_VERSION;
    }

    /**
     * Test seam: exposes whether a string contains anything that would
     * be flagged as a secret — used by the quality report to ensure
     * no live credential escaped redaction.
     */
    public boolean containsLikelySecret(String text) {
        if (text == null || text.isEmpty()) return false;
        return ASSIGNMENT_PATTERN.matcher(text).find() || HIGH_ENTROPY.matcher(text).find();
    }
}