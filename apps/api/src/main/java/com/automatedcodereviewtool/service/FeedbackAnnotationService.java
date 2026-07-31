package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.entity.Annotation;
import com.automatedcodereviewtool.entity.Finding;
import com.automatedcodereviewtool.repository.AnnotationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Converts finding dispositions into {@link Annotation} records
 * with reviewer-aware, database-enforced idempotency.
 *
 * <p>Key design decisions:
 * <ul>
 *   <li>Idempotency is enforced by a UNIQUE database constraint on
 *       {@code idempotency_key} (finding_id + reviewer + action), not
 *       by scanning free-text rationale.</li>
 *   <li>A changed decision from the same reviewer creates a new annotation
 *       that supersedes the previous one (preserves history).</li>
 *   <li>Different reviewers create independent annotations — no conflict.</li>
 *   <li>Manual annotations are never overwritten by automated feedback.</li>
 * </ul>
 */
@Service
public class FeedbackAnnotationService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackAnnotationService.class);

    private static final String SOURCE_FEEDBACK = "finding_feedback";
    private static final String SOURCE_MANUAL = "manual_annotation";

    private final AnnotationRepository annotationRepository;

    public FeedbackAnnotationService(AnnotationRepository annotationRepository) {
        this.annotationRepository = annotationRepository;
    }

    /**
     * Create an annotation from a finding whose status has changed.
     *
     * <p>Idempotency: if the same reviewer submits the same action for
     * the same finding, the database UNIQUE constraint on
     * {@code idempotency_key} prevents duplicates and we return the
     * existing annotation.</p>
     *
     * <p>If the reviewer changes their decision, we create a new annotation
     * that supersedes the previous one.</p>
     *
     * @return the annotation (existing or newly created)
     */
    @Transactional
    public Annotation annotateFromFinding(Finding finding, UUID reviewerUserId, String feedbackAction) {
        if (finding == null || finding.getId() == null) {
            throw new IllegalArgumentException("Finding must not be null and must have an ID");
        }

        String action = normalizeAction(finding.getStatus(), feedbackAction);
        String idempotencyKey = buildIdempotencyKey(finding.getId(), reviewerUserId, action);

        // Check if this exact combination already exists.
        Optional<Annotation> existing = annotationRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.debug("Idempotent annotation for finding {} reviewer {} action {} — returning existing",
                    finding.getId(), reviewerUserId, action);
            return existing.get();
        }

        // Check if the reviewer has an active annotation for this finding.
        Optional<Annotation> previous = annotationRepository
                .findFirstByFindingIdAndReviewerUserIdAndResolutionStateOrderByCreatedAtDesc(
                        finding.getId(), reviewerUserId, "active");

        Annotation annotation = buildAnnotation(finding, reviewerUserId, action);

        if (previous.isPresent()) {
            // Supersede the previous annotation.
            Annotation prev = previous.get();
            prev.setResolutionState("superseded");
            prev.setSupersedesAnnotationId(null); // prev supersedes whatever superseded it
            annotation.setSupersedesAnnotationId(prev.getId());
            annotationRepository.save(prev);
            log.debug("Superseding annotation {} for finding {} reviewer {}",
                    prev.getId(), finding.getId(), reviewerUserId);
        }

        annotation.setIdempotencyKey(idempotencyKey);
        return annotationRepository.save(annotation);
    }

    /**
     * Record manual annotation (reviewer-driven, not from finding status).
     *
     * <p>Manual annotations are never overwritten by automated feedback.
     * Each manual submission creates a new record.</p>
     */
    @Transactional
    public Annotation annotateManual(UUID codeSampleId,
                                     String antiPatternId,
                                     String labelState,
                                     UUID reviewerUserId,
                                     String notes) {
        String action = mapLabelStateToAction(labelState);
        // Manual annotations: include codeSampleId in idempotency key so
        // re-annotating the same sample+pattern+reviewer is deduped.
        String idempotencyKey = buildManualIdempotencyKey(codeSampleId, antiPatternId, reviewerUserId, action);

        Optional<Annotation> existing = annotationRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        Annotation annotation = new Annotation();
        annotation.setCodeSampleId(codeSampleId);
        annotation.setAntiPatternId(antiPatternId);
        annotation.setLabelState(labelState);
        annotation.setLineStart(0);
        annotation.setLineEnd(0);
        annotation.setSource(SOURCE_MANUAL);
        annotation.setConfidence(BigDecimal.ONE);
        annotation.setReviewerUserId(reviewerUserId);
        annotation.setFindingId(null);
        annotation.setFeedbackAction(action);
        annotation.setResolutionState("active");
        annotation.setTrustLevel("human_single");
        annotation.setRationale(notes);
        annotation.setCreatedAt(OffsetDateTime.now());
        annotation.setUpdatedAt(OffsetDateTime.now());
        annotation.setIdempotencyKey(idempotencyKey);
        return annotationRepository.save(annotation);
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private String normalizeAction(String findingStatus, String requestedAction) {
        if (requestedAction != null && !requestedAction.isBlank()) {
            return requestedAction.toLowerCase(java.util.Locale.ROOT);
        }
        return switch (findingStatus == null ? "open" : findingStatus.toLowerCase(java.util.Locale.ROOT)) {
            case "accepted" -> "accepted";
            case "dismissed" -> "dismissed";
            case "fixed" -> "fixed";
            default -> "accepted";
        };
    }

    private String mapLabelStateToAction(String labelState) {
        return switch (labelState == null ? "positive" : labelState.toLowerCase(java.util.Locale.ROOT)) {
            case "positive" -> "manual_positive";
            case "negative" -> "manual_negative";
            default -> "manual_uncertain";
        };
    }

    private String buildIdempotencyKey(UUID findingId, UUID reviewerUserId, String action) {
        return String.format("%s:%s:%s", findingId, reviewerUserId, action);
    }

    private String buildManualIdempotencyKey(UUID codeSampleId, String antiPatternId,
                                             UUID reviewerUserId, String action) {
        return String.format("manual:%s:%s:%s:%s", codeSampleId, antiPatternId, reviewerUserId, action);
    }

    private Annotation buildAnnotation(Finding f, UUID reviewerUserId, String action) {
        Annotation a = new Annotation();
        a.setCodeSampleId(f.getCodeSampleId());
        a.setAntiPatternId(f.getAntiPattern());
        a.setLabelState(mapActionToLabelState(action));
        a.setLineStart(f.getLineStart() == null ? 0 : f.getLineStart());
        a.setLineEnd(f.getLineEnd() == null
                ? (f.getLineStart() == null ? 0 : f.getLineStart())
                : f.getLineEnd());
        a.setSource(SOURCE_FEEDBACK);
        a.setConfidence(BigDecimal.ONE);
        a.setReviewerUserId(reviewerUserId);
        a.setFindingId(f.getId());
        a.setFeedbackAction(action);
        a.setResolutionState("active");
        a.setTrustLevel("finding_feedback");
        a.setRationale("finding_id=" + f.getId());
        a.setCreatedAt(OffsetDateTime.now());
        a.setUpdatedAt(OffsetDateTime.now());
        return a;
    }

    private String mapActionToLabelState(String action) {
        return switch (action) {
            case "dismissed", "manual_negative" -> "negative";
            case "manual_uncertain" -> "uncertain";
            default -> "positive";
        };
    }
}
