package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.service.MlWorkerService;
import com.automatedcodereviewtool.service.TaxonomyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity test: Java {@code MlWorkerService.computeQualityScore} must
 * match the reference Python implementation on every case in
 * {@code contracts/quality_score_cases.json}.
 *
 * <p>The same JSON fixture is consumed by the Python test suite.</p>
 */
@SpringBootTest
class QualityScoreParityTest {

    @Autowired
    private MlWorkerService mlWorkerService;

    @Autowired
    private TaxonomyService taxonomyService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parityAgainstSharedFixture() throws Exception {
        java.io.InputStream is = getClass().getResourceAsStream("/quality_score_cases.json");
        assertThat(is).as("contracts/quality_score_cases.json must be on test classpath").isNotNull();
        java.util.List<Case> cases = MAPPER.readValue(is, MAPPER.getTypeFactory().constructCollectionType(List.class, Case.class));
        assertThat(cases).isNotEmpty();
        for (Case c : cases) {
            BigDecimal got = MlWorkerService.computeQualityScore(
                    new com.automatedcodereviewtool.dto.MlReviewResponse(
                            c.findings == null ? List.of() : c.findings.stream()
                                    .map(f -> new com.automatedcodereviewtool.dto.MlFinding("path", null, 1, 1, f.antiPattern(), "category", f.severity(), f.confidence(), "explanation"))
                                    .toList(),
                            c.qualityScore == null ? null : new BigDecimal(c.qualityScore),
                            c.processingTimeMs == null ? 0 : c.processingTimeMs,
                            c.windowsProcessed == null ? 0 : c.windowsProcessed,
                            "model", "v1", "1.0.0"
                    ));
            BigDecimal expected = new BigDecimal(c.expected).setScale(2, RoundingMode.HALF_UP);
            assertThat(got).as("case '%s' (seed=%s)", c.name, c.seed)
                    .isEqualByComparingTo(expected);
        }
    }

    @Test
    void javaTrainableIdsMatchesCanonicalTaxonomy() {
        // The Java service must load the same canonical taxonomy as Python.
        // Verify each entry has the expected structure and that the
        // trainable list is non-empty and contains well-known IDs.
        assertThat(taxonomyService.trainableIds()).isNotEmpty();
        assertThat(taxonomyService.contains("SECURITY_HARDCODED_SECRET")).isTrue();
        assertThat(taxonomyService.isTrainable("SECURITY_HARDCODED_SECRET")).isTrue();
        assertThat(taxonomyService.categoryOf("SECURITY_HARDCODED_SECRET"))
                .isEqualTo("SECURITY");
        assertThat(taxonomyService.contains("PERFORMANCE_N_PLUS_1")).isFalse();
        assertThat(taxonomyService.contains("PERFORMANCE_N_PLUS_ONE")).isTrue();
    }

    @Test
    void confidenceAboveOneIsClamped() {
        // A confidence of 2.5 (e.g. from a misconfigured model head) must
        // be treated identically to 1.0 so that the score stays in [0,100].
        BigDecimal clamped = MlWorkerService.computeQualityScore(
                new com.automatedcodereviewtool.dto.MlReviewResponse(
                        List.of(new com.automatedcodereviewtool.dto.MlFinding(
                                "path", null, 1, 1, "TEST_FINDING", "category", "major", new BigDecimal("2.5"), "explanation")),
                        null, 0, 0,
                        "model", "v1", "1.0.0"));
        BigDecimal atOne = MlWorkerService.computeQualityScore(
                new com.automatedcodereviewtool.dto.MlReviewResponse(
                        List.of(new com.automatedcodereviewtool.dto.MlFinding(
                                "path", null, 1, 1, "TEST_FINDING", "category", "major", BigDecimal.ONE, "explanation")),
                        null, 0, 0,
                        "model", "v1", "1.0.0"));
        assertThat(clamped).isEqualByComparingTo(atOne);
        assertThat(clamped).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void confidenceBelowZeroIsClamped() {
        // A negative confidence (e.g. -0.4 from a buggy regression head) must
        // be clamped to 0 so it never *increases* the penalty.
        BigDecimal clamped = MlWorkerService.computeQualityScore(
                new com.automatedcodereviewtool.dto.MlReviewResponse(
                        List.of(new com.automatedcodereviewtool.dto.MlFinding(
                                "path", null, 1, 1, "TEST_FINDING", "category", "major", new BigDecimal("-0.4"), "explanation")),
                        null, 0, 0,
                        "model", "v1", "1.0.0"));
        BigDecimal atZero = MlWorkerService.computeQualityScore(
                new com.automatedcodereviewtool.dto.MlReviewResponse(
                        List.of(new com.automatedcodereviewtool.dto.MlFinding(
                                "path", null, 1, 1, "TEST_FINDING", "category", "major", BigDecimal.ZERO, "explanation")),
                        null, 0, 0,
                        "model", "v1", "1.0.0"));
        assertThat(clamped).isEqualByComparingTo(atZero);
    }

    private record Finding(String antiPattern, String severity, BigDecimal confidence) {
        private Finding { if (confidence == null) confidence = BigDecimal.ZERO; }
    }
    private record Case(
            String name,
            Integer seed,
            List<Finding> findings,
            String qualityScore,
            Integer processingTimeMs,
            Integer windowsProcessed,
            String expected,
            String notes
    ) {}
}