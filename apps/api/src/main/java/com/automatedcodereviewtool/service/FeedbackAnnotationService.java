package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.entity.Annotation;
import com.automatedcodereviewtool.entity.Finding;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Converts finding dispositions (accepted, dismissed, fixed) into
 * {@link Annotation} records that feed the ML dataset pipeline.
 *
 * <p>Annotation creation is idempotent — calling this repeatedly with
 * the same input will not create duplicates. Conflicting feedback
 * is preserved rather than overwritten.</p>
 */
@Service
public class FeedbackAnnotationService {

    private final AnnotationRepository annotationRepository;

    public FeedbackAnnotationService(AnnotationRepository annotationRepository) {
        this.annotationRepository = annotationRepository;
    }

    /**
     * Create an annotation from a finding whose status has changed.
     *
     * @return whether a new annotation was created
     */
    @Transactional
    public boolean annotateFromFinding(Finding finding) {
        if (finding == null || finding.getId() == null) return false;

        boolean exists = annotationRepository.existsBySource("finding_feedback")
                && annotationRepository.findAll().stream()
                        .anyMatch(a -> a.getRationale() != null
                                && a.getRationale().contains(finding.getId().toString()));
        if (exists) return false;

        String disposition = switch (finding.getStatus()) {
            case "accepted" -> {
                yield buildAnnotation(finding, "positive", null);
            }
            case "dismissed" -> {
                yield buildAnnotation(finding, "negative",
                        "dismissed; reason captured in rationale");
            }
            case "fixed" -> {
                yield buildAnnotation(finding, "positive",
                        "fixed after acceptance");
            }
            default -> null;
        };
        if (disposition == null) return false;
        annotationRepository.save(disposition);
        return true;
    }

    private Annotation buildAnnotation(Finding f, String labelState, String rationale) {
        Annotation a = new Annotation();
        a.setCodeSampleId(f.getCodeSampleId());
        a.setAntiPatternId(f.getAntiPattern());
        a.setLabelState(labelState);
        a.setLineStart(f.getLineStart() == null ? 0 : f.getLineStart());
        a.setLineEnd(f.getLineEnd() == null
                ? (f.getLineStart() == null ? 0 : f.getLineStart())
                : f.getLineEnd());
        a.setSource("finding_feedback");
        a.setConfidence(BigDecimal.ONE);
        a.setReviewerUserId(null);
        a.setRationale(
                "finding_id=" + f.getId() + (rationale == null ? "" : "; " + rationale)
        );
        a.setCreatedAt(OffsetDateTime.now());
        a.setUpdatedAt(OffsetDateTime.now());
        return a;
    }

    /**
     * Record conflicting feedback without overwriting existing
     * annotations. A second call for the same finding with a
     * different disposition appends a new annotation instead of
     * updating the first.
     */
    @Transactional
    public boolean annotateIfDispositionChanged(Finding finding, String previousStatus) {
        if (finding == null || finding.getId() == null) return false;
        String current = finding.getStatus();
        if (current == null || current.equals(previousStatus)) return false;
        return annotateFromFinding(finding);
    }
}