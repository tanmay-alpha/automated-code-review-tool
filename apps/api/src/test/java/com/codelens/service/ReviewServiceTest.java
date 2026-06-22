package com.codelens.service;

import com.codelens.dto.MlFinding;
import com.codelens.dto.MlReviewResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.codelens.service.ReviewService.computeQualityScore;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals("python", ReviewService.detectLanguage(diff));
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
        assertEquals("java", ReviewService.detectLanguage(diff));
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
        assertEquals("javascript", ReviewService.detectLanguage(diff));
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
        assertEquals("python", ReviewService.detectLanguage(diff));
    }

    @Test
    void testDetectLanguageDefaultPython() {
        // No recognized extension → default python
        String diff = "diff --git a/README.md b/README.md\n+hello";
        assertEquals("python", ReviewService.detectLanguage(diff));
    }

    @Test
    void testDetectLanguageEmpty() {
        assertEquals("python", ReviewService.detectLanguage(""));
        assertEquals("python", ReviewService.detectLanguage(null));
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
        assertEquals("javascript", ReviewService.detectLanguage(diff));
    }

    // ---- computeQualityScore ----

    @Test
    void testComputeQualityScore_nullResponse_returnsZero() {
        assertEquals(BigDecimal.ZERO, computeQualityScore(null));
    }

    @Test
    void testComputeQualityScore_nullScore_noFindings_returns100() {
        MlReviewResponse resp = new MlReviewResponse(null, null, 0, 0);
        assertEquals(new BigDecimal("100"), computeQualityScore(resp));
    }

    @Test
    void testComputeQualityScore_validScore_clamped() {
        MlReviewResponse resp = new MlReviewResponse(List.of(), new BigDecimal("85.5"), 0, 0);
        assertEquals(0, new BigDecimal("85.50").compareTo(computeQualityScore(resp)));
    }

    @Test
    void testComputeQualityScore_scoreOver100_clamped() {
        MlReviewResponse resp = new MlReviewResponse(List.of(), new BigDecimal("150"), 0, 0);
        assertEquals(0, new BigDecimal("100.00").compareTo(computeQualityScore(resp)));
    }

    @Test
    void testComputeQualityScore_negativeScore_clamped() {
        MlReviewResponse resp = new MlReviewResponse(List.of(), new BigDecimal("-10"), 0, 0);
        assertEquals(0, BigDecimal.ZERO.compareTo(computeQualityScore(resp)));
    }

    @Test
    void testComputeQualityScore_fallbackFromFindings() {
        // No qualityScore → falls back to penalty calculation
        // severity "major" weight=10, "low" weight=5 (default branch)
        // penalty = 10*0.9 + 5*0.8 = 9.0 + 4.0 = 13.0
        // score = 100 - 13 = 87.00
        List<MlFinding> findings = List.of(
                new MlFinding(1, 5, "GodClass", "structural", "major", new BigDecimal("0.9"), "too big"),
                new MlFinding(6, 10, "MagicNumber", "readability", "low", new BigDecimal("0.8"), "magic")
        );
        MlReviewResponse resp = new MlReviewResponse(findings, null, 0, 0);
        assertEquals(0, new BigDecimal("87.00").compareTo(computeQualityScore(resp)));
    }
}