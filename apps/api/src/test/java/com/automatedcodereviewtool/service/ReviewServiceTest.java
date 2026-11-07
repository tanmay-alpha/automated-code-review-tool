package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.dto.MlFinding;
import com.automatedcodereviewtool.dto.MlReviewResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.automatedcodereviewtool.service.MlWorkerService.computeQualityScore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ReviewService} helpers that don't need a Spring context.
 *
 * <p>Tests are in the same package so they can access package-private
 * members ({@code detectLanguage}, {@code computeQualityScore}).</p>
 */
class ReviewServiceTest {

    // ---- detectLanguage ----

    @Test
    void testDetectLanguagePython() {
        String diff = """
                diff --git a/src/main.py b/src/main.py
                index 123..456 100644
                --- a/src/main.py
                +++ b/src/main.py
                @@ -1,3 +1,4 @@
                +import os
                +import sys
                +print("hello")
                +x = 1
                """;
        assertEquals("python", MlWorkerService.detectLanguage(diff));
    }

    @Test
    void testDetectLanguageJava() {
        String diff = """
                diff --git a/src/Main.java b/src/Main.java
                index 123..456 100644
                --- a/src/Main.java
                +++ b/src/Main.java
                @@ -1,3 +1,4 @@
                +package com.example;
                +
                +public class Main {}
                """;
        assertEquals("java", MlWorkerService.detectLanguage(diff));
    }

    @Test
    void testDetectLanguageJavaScript() {
        String diff = """
                diff --git a/src/app.ts b/src/app.ts
                index 123..456 100644
                --- a/src/app.ts
                +++ b/src/app.ts
                @@ -1,3 +1,4 @@
                +import React from "react";
                +const App = () => <div>hello</div>;
                +export default App;
                """;
        assertEquals("javascript", MlWorkerService.detectLanguage(diff));
    }

    @Test
    void testDetectLanguagePythonOverJava() {
        // More python lines than java → python wins
        String diff = """
                diff --git a/a.py b/a.py
                index 123..456 100644
                --- a/a.py
                +++ b/a.py
                @@ -1,3 +1,4 @@
                +import os
                +import sys
                +print("hello")
                diff --git a/B.java b/B.java
                index 123..456 100644
                --- a/B.java
                +++ b/B.java
                @@ -1,3 +1,4 @@
                +package com.example;
                +
                +public class B {}
                """;
        assertEquals("python", MlWorkerService.detectLanguage(diff));
    }

    @Test
    void testDetectLanguageDefaultPython() {
        // No recognized extension → default python
        String diff = "diff --git a/README.md b/README.md\n+hello";
        assertEquals("python", MlWorkerService.detectLanguage(diff));
    }

    @Test
    void testDetectLanguageEmpty() {
        assertEquals("python", MlWorkerService.detectLanguage(""));
        assertEquals("python", MlWorkerService.detectLanguage(null));
    }

    @Test
    void testDetectLanguageJsx() {
        String diff = """
                diff --git a/App.jsx b/App.jsx
                index 123..456 100644
                --- a/App.jsx
                +++ a/App.jsx
                @@ -1,3 +1,4 @@
                +import React from "react";
                +const App = () => <div>hello</div>;
                +export default App;
                """;
        assertEquals("javascript", MlWorkerService.detectLanguage(diff));
    }

    // ---- computeQualityScore ----

    @Test
    void testComputeQualityScore_nullResponse_returnsZero() {
        assertEquals(BigDecimal.ZERO, computeQualityScore(null));
    }

    @Test
    void testComputeQualityScore_nullScore_noFindings_returns100() {
        MlReviewResponse resp = new MlReviewResponse(null, null, 0, 0, "fallback", "v1", "1.0.0");
        assertEquals(new BigDecimal("100"), computeQualityScore(resp));
    }

    @Test
    void testComputeQualityScore_validScore_clamped() {
        MlReviewResponse resp = new MlReviewResponse(List.of(), new BigDecimal("85.5"), 0, 0, "fallback", "v1", "1.0.0");
        assertEquals(0, new BigDecimal("85.50").compareTo(computeQualityScore(resp)));
    }

    @Test
    void testComputeQualityScore_scoreOver100_clamped() {
        MlReviewResponse resp = new MlReviewResponse(List.of(), new BigDecimal("150"), 0, 0, "fallback", "v1", "1.0.0");
        assertEquals(0, new BigDecimal("100.00").compareTo(computeQualityScore(resp)));
    }

    @Test
    void testComputeQualityScore_negativeScore_clamped() {
        MlReviewResponse resp = new MlReviewResponse(List.of(), new BigDecimal("-10"), 0, 0, "fallback", "v1", "1.0.0");
        assertEquals(0, BigDecimal.ZERO.compareTo(computeQualityScore(resp)));
    }

    @Test
    void testComputeQualityScore_fallbackFromFindings() {
        // No qualityScore → falls back to penalty calculation
        // severity "major" weight=10, "low" weight=3 (default branch)
        // penalty = 10*0.9 + 3*0.8 = 9.0 + 2.4 = 11.4
        // score = 100 - 11.4 = 88.60
        List<MlFinding> findings = List.of(
                new MlFinding("app.py", null, 1, 5, "GodClass", "structural", "major", new BigDecimal("0.9"), "too big"),
                new MlFinding("app.py", null, 6, 10, "MagicNumber", "readability", "low", new BigDecimal("0.8"), "magic")
        );
        MlReviewResponse resp = new MlReviewResponse(findings, null, 0, 0, "fallback", "v1", "1.0.0");
        assertEquals(0, new BigDecimal("88.60").compareTo(computeQualityScore(resp)));
    }

    // ---- diff → (file, line) mapping ----

    private static final String TWO_FILE_DIFF = """
            diff --git a/src/a.py b/src/a.py
            --- a/src/a.py
            +++ b/src/a.py
            @@ -8,3 +8,4 @@ def f():
             context_a8 = 1
            -removed = 2
            +added_a9 = 3
            +added_a10 = 4
             context_a11 = 5
            diff --git a/src/b.py b/src/b.py
            --- a/src/b.py
            +++ b/src/b.py
            @@ -10,2 +10,2 @@ def g():
            -old = 1
            +added_b10 = 1
             context_b11 = 2
            """;

    private static MlFinding finding(String file, Integer line) {
        return new MlFinding(file, null, line, line, "SECURITY_HARDCODED_SECRET", "SECURITY",
                "critical", new BigDecimal("0.9"), "x");
    }

    @Test
    void newSideLines_areKeyedByFileAndNewLineNumber() {
        Map<String, Map<Integer, String>> lines = ReviewService.newSideLinesByFile(TWO_FILE_DIFF);

        assertEquals(Map.of(8, "context_a8 = 1", 9, "added_a9 = 3", 10, "added_a10 = 4",
                11, "context_a11 = 5"), lines.get("src/a.py"));
        assertEquals(Map.of(10, "added_b10 = 1", 11, "context_b11 = 2"), lines.get("src/b.py"));
    }

    @Test
    void resolveFilePath_usesWorkerFilePath_whenSameLineExistsInSeveralFiles() {
        Map<String, Map<Integer, String>> lines = ReviewService.newSideLinesByFile(TWO_FILE_DIFF);

        // Line 10 exists in both files; the worker's filePath decides.
        assertEquals("src/a.py", ReviewService.resolveFilePath(finding("src/a.py", 10), lines));
        assertEquals("src/b.py", ReviewService.resolveFilePath(finding("src/b.py", 10), lines));
    }

    @Test
    void resolveFilePath_acceptsContextLines_andRejectsLinesOutsideTheDiff() {
        Map<String, Map<Integer, String>> lines = ReviewService.newSideLinesByFile(TWO_FILE_DIFF);

        assertEquals("src/a.py", ReviewService.resolveFilePath(finding("src/a.py", 8), lines));
        assertNull(ReviewService.resolveFilePath(finding("src/a.py", 50), lines));
        assertNull(ReviewService.resolveFilePath(finding("src/missing.py", 9), lines));
        assertNull(ReviewService.resolveFilePath(finding("src/a.py", null), lines));
    }

    @Test
    void resolveFilePath_withoutWorkerPath_requiresAnUnambiguousLine() {
        Map<String, Map<Integer, String>> lines = ReviewService.newSideLinesByFile(TWO_FILE_DIFF);

        assertEquals("src/a.py", ReviewService.resolveFilePath(finding(null, 9), lines));
        assertNull(ReviewService.resolveFilePath(finding(null, 10), lines));
    }
}