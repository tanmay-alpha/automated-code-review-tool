package com.codelens.controller;

import com.codelens.dto.MlFinding;
import com.codelens.dto.MlReviewResponse;
import com.codelens.dto.request.FindingActionRequest;
import com.codelens.dto.request.ScanFileRequest;
import com.codelens.dto.response.FindingActionResponse;
import com.codelens.dto.response.ScanFileResponse;
import com.codelens.entity.Finding;
import com.codelens.entity.PullRequestEntity;
import com.codelens.entity.Repository;
import com.codelens.repository.FindingRepository;
import com.codelens.service.MlWorkerService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Ad-hoc / on-demand review endpoints — distinct from the PR-driven webhook flow.
 *
 * <ul>
 *   <li>{@code POST /api/scan/file} — paste-source or path-style scan,
 *       returns a list of findings + a quality score. <b>No PR/Repo
 *       persistence.</b></li>
 *   <li>{@code POST /api/scan/action} — record a developer's disposition
 *       (accept / dismiss / fix) on an existing finding.</li>
 * </ul>
 *
 * <p>Both endpoints require a valid GitHub access token (handled by
 * the {@code JwtAuthFilter}); they are <b>not</b> publicly callable.</p>
 */
@RestController
@RequestMapping("/api/scan")
public class ScanController {

    private static final Logger log = LoggerFactory.getLogger(ScanController.class);

    private static final Set<String> VALID_ACTIONS = Set.of("accept", "dismiss", "fix");

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

        MlReviewResponse ml = mlWorkerService.reviewFile(
                req.content(),
                req.language()
        );

        List<MlFinding> findings = ml.findings() == null ? List.of() : ml.findings();
        BigDecimal quality = ml.qualityScore() == null ? BigDecimal.valueOf(80) : ml.qualityScore();

        return ResponseEntity.ok(new ScanFileResponse(findings, quality, req.language()));
    }

    /**
     * POST /api/scan/action
     *
     * <p>Records a developer disposition on a finding. Updates
     * {@code status} + {@code disposition_at} on the Finding row.</p>
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
    @PostMapping("/action")
    public ResponseEntity<FindingActionResponse> recordAction(
            @Valid @RequestBody FindingActionRequest req,
            @AuthenticationPrincipal UserDetails caller) {

        String action = req.action().toLowerCase(Locale.ROOT);
        if (!VALID_ACTIONS.contains(action)) {
            throw new IllegalArgumentException(
                    "Invalid action '" + req.action() + "' — expected one of " + VALID_ACTIONS);
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

        finding.setStatus(action);
        finding.setDispositionAt(Instant.now());
        findingRepository.save(finding);

        log.info("Finding {} disposition={} (actor={})",
                finding.getId(), action, caller == null ? "?" : caller.getUsername());

        return ResponseEntity.ok(new FindingActionResponse(
                finding.getId(), action, finding.getStatus()));
    }

    /**
     * Returns true when the given finding's PR's repository's owner
     * matches the caller's GitHub login. Treats unauthenticated callers
     * as non-owners (defence in depth — the filter chain should already
     * have rejected them).
     */
    private static boolean isOwnedBy(Finding finding, UserDetails caller) {
        if (caller == null || caller.getUsername() == null) return false;
        PullRequestEntity pr = finding.getPullRequest();
        if (pr == null) return false;
        Repository repo = pr.getRepo();
        if (repo == null || repo.getOwner() == null) return false;
        String ownerLogin = repo.getOwner().getGithubUsername();
        return caller.getUsername().equalsIgnoreCase(ownerLogin);
    }
}