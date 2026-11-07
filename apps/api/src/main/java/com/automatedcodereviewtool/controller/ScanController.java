package com.automatedcodereviewtool.controller;

import com.automatedcodereviewtool.dto.MlFinding;
import com.automatedcodereviewtool.dto.MlReviewResponse;
import com.automatedcodereviewtool.dto.request.FindingActionRequest;
import com.automatedcodereviewtool.dto.request.ScanFileRequest;
import com.automatedcodereviewtool.dto.response.FindingActionResponse;
import com.automatedcodereviewtool.dto.response.ScanFileResponse;
import com.automatedcodereviewtool.entity.Finding;
import com.automatedcodereviewtool.entity.PullRequestEntity;
import com.automatedcodereviewtool.entity.Repository;
import com.automatedcodereviewtool.entity.User;
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
import java.util.UUID;

/**
 * Ad-hoc / on-demand review endpoints — distinct from the PR-driven webhook flow.
 *
 * <ul>
 *   <li>{@code POST /api/scan/file} — scan one file's contents and return
 *       findings + a quality score. <b>Nothing is persisted.</b> Accepts a
 *       JWT or an API key.</li>
 *   <li>{@code POST /api/scan/action} — record a developer's disposition
 *       (accept / dismiss / fix) on an existing PR finding. JWT only.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/scan")
public class ScanController {

    private static final Logger log = LoggerFactory.getLogger(ScanController.class);

    /** Request verb → value allowed by the {@code findings.status} CHECK constraint. */
    private static final Map<String, String> STATUS_BY_ACTION = Map.of(
            "accept", "accepted",
            "dismiss", "dismissed",
            "fix", "fixed");

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
     * <p>Records a developer disposition on a finding: sets {@code status}
     * ({@code accepted} / {@code dismissed} / {@code fixed}) and
     * {@code disposition_at}.</p>
     *
     * <p><b>Authorization:</b> the finding must belong to a repository
     * owned by the authenticated user. If the user does not own the
     * repository that owns the PR that owns the finding, we return 404
     * (not 403) to avoid leaking the existence of findings the caller
     * does not have permission to inspect.</p>
     *
     * @return 200 with the applied action; 404 if finding not found or
     *         not owned by caller; 400 if action verb is unrecognized.
     */
    // The ownership check walks lazy finding -> PR -> repo -> owner
    // associations, so the load, check, and update share one transaction
    // instead of relying on open-in-view.
    @PostMapping("/action")
    @Transactional
    public ResponseEntity<FindingActionResponse> recordAction(
            @Valid @RequestBody FindingActionRequest req,
            @AuthenticationPrincipal User caller) {

        String action = req.action().toLowerCase(Locale.ROOT);
        String status = STATUS_BY_ACTION.get(action);
        if (status == null) {
            throw new IllegalArgumentException(
                    "Invalid action '" + req.action() + "' — expected one of " + STATUS_BY_ACTION.keySet());
        }

        Finding finding = findingRepository.findById(req.findingId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Finding " + req.findingId() + " not found"));

        // Ownership check: the finding -> PR -> repo must belong to the caller.
        // Use 404 (not 403) so we don't reveal the existence of out-of-scope findings.
        if (!isOwnedBy(finding, caller)) {
            throw new EntityNotFoundException(
                    "Finding " + req.findingId() + " not found");
        }

        finding.setStatus(status);
        finding.setDispositionAt(Instant.now());
        findingRepository.save(finding);

        log.info("Finding {} disposition={} (actor={})",
                finding.getId(), action, caller == null ? "?" : caller.getGithubUsername());

        return ResponseEntity.ok(new FindingActionResponse(
                finding.getId(), action, finding.getStatus()));
    }

    /**
     * Returns true when the finding's PR's repository is owned by the
     * caller (compared by user id). Treats unauthenticated callers
     * as non-owners (defence in depth — the filter chain should already
     * have rejected them).
     */
    private static boolean isOwnedBy(Finding finding, User caller) {
        if (caller == null || caller.getId() == null) return false;
        PullRequestEntity pr = finding.getPullRequest();
        if (pr == null) return false;
        Repository repo = pr.getRepo();
        if (repo == null || repo.getOwner() == null || repo.getOwner().getId() == null) return false;
        return caller.getId().equals(repo.getOwner().getId());
    }
}
