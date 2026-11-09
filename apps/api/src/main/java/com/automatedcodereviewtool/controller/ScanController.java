package com.automatedcodereviewtool.controller;

import com.automatedcodereviewtool.dto.MlFinding;
import com.automatedcodereviewtool.dto.MlReviewResponse;
import com.automatedcodereviewtool.dto.request.FindingActionRequest;
import com.automatedcodereviewtool.dto.request.ScanFileRequest;
import com.automatedcodereviewtool.dto.response.FindingActionResponse;
import com.automatedcodereviewtool.dto.response.ScanFileResponse;
import com.automatedcodereviewtool.entity.Finding;
import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.exception.InvalidRequestException;
import com.automatedcodereviewtool.repository.FindingRepository;
import com.automatedcodereviewtool.service.MlWorkerService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Ad-hoc / on-demand review endpoints — distinct from the PR-driven webhook flow.
 *
 * <ul>
 *   <li>{@code POST /api/scan/file} — scan one file's contents and return
 *       findings + a quality score. <b>Nothing is persisted.</b> Accepts a
 *       JWT or an API key.</li>
 *   <li>{@code POST /api/scan/action} — triage an existing PR finding
 *       (accept / dismiss / fix / false_positive / reopen). JWT only.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/scan")
public class ScanController {

    private static final Logger log = LoggerFactory.getLogger(ScanController.class);

    /** Request verb → value allowed by the {@code findings.status} CHECK constraint (V19). */
    private static final Map<String, String> STATUS_BY_ACTION = Map.of(
            "accept", "accepted",
            "dismiss", "dismissed",
            "fix", "fixed",
            "false_positive", "false_positive",
            "reopen", "open");

    /** Actions that must be justified: they suppress a finding without fixing it. */
    private static final Set<String> REASON_REQUIRED = Set.of("dismiss", "false_positive");

    static final int MAX_REASON_CHARS = 500;

    private final MlWorkerService mlWorkerService;
    private final FindingRepository findingRepository;

    public ScanController(MlWorkerService mlWorkerService,
                          FindingRepository findingRepository) {
        this.mlWorkerService = mlWorkerService;
        this.findingRepository = findingRepository;
    }

    /**
     * POST /api/scan/file
     *
     * <p>Ad-hoc file scan. Persists nothing — returns the findings
     * + score so the caller can render them inline.</p>
     */
    @PostMapping("/file")
    public ResponseEntity<ScanFileResponse> scanFile(
            @Valid @RequestBody ScanFileRequest req) {

        // language is required by ScanFileRequest validation.
        String language = req.language();
        MlReviewResponse ml = mlWorkerService.reviewFile(req.content(), language, req.filePath());

        List<MlFinding> findings = ml.findings() == null ? List.of() : ml.findings();
        BigDecimal quality = MlWorkerService.computeQualityScore(ml);

        return ResponseEntity.ok(new ScanFileResponse(findings, quality, language));
    }

    /**
     * POST /api/scan/action
     *
     * <p>Records a triage decision: sets {@code status}, {@code triage_reason}
     * (trimmed, ≤ 500 chars; required for {@code dismiss} and
     * {@code false_positive}), {@code triaged_at} and {@code triaged_by}.
     * Every action, including {@code reopen}, overwrites the previous
     * decision, so the row always describes the latest one.</p>
     *
     * <p><b>Authorization:</b> the finding must belong to a repository
     * owned by the authenticated user. Missing and foreign findings both
     * return 404 (not 403) so the existence of out-of-scope findings is
     * not revealed.</p>
     *
     * @return 200 with the new triage state; 404 if the finding is not found
     *         or not owned by the caller; 400 for an unknown action or a
     *         missing/oversized reason.
     */
    @PostMapping("/action")
    @Transactional
    public ResponseEntity<FindingActionResponse> recordAction(
            @Valid @RequestBody FindingActionRequest req,
            @AuthenticationPrincipal User caller) {

        String action = req.action().trim().toLowerCase(Locale.ROOT);
        String status = STATUS_BY_ACTION.get(action);
        if (status == null) {
            throw new InvalidRequestException(
                    "action: must be one of " + new TreeSet<>(STATUS_BY_ACTION.keySet()));
        }
        String reason = req.reason() == null || req.reason().isBlank() ? null : req.reason().trim();
        if (reason == null && REASON_REQUIRED.contains(action)) {
            throw new InvalidRequestException("reason: is required for action '" + action + "'");
        }
        if (reason != null && reason.length() > MAX_REASON_CHARS) {
            throw new InvalidRequestException("reason: must be at most " + MAX_REASON_CHARS + " characters");
        }

        Finding finding = findingRepository.findOwnedById(req.findingId(), caller.getId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Finding " + req.findingId() + " not found"));

        Instant now = Instant.now();
        finding.setStatus(status);
        finding.setTriageReason(reason);
        finding.setTriagedAt(now);
        finding.setTriagedBy(caller.getId());
        findingRepository.save(finding);

        log.info("Finding {} triaged: action={} status={} (actor={})",
                finding.getId(), action, status, caller.getGithubUsername());

        return ResponseEntity.ok(new FindingActionResponse(
                finding.getId(), status, reason, now, caller.getGithubUsername()));
    }
}
