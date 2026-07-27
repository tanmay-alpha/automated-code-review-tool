package com.codelens.controller;

import com.codelens.dto.ReviewResponse;
import com.codelens.entity.PullRequestEntity;
import com.codelens.entity.Repository;
import com.codelens.repository.FindingRepository;
import com.codelens.repository.PullRequestRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Read-only endpoints for retrieving review results.
 *
 * <p>Both endpoints look up the PR by its internal UUID. The user
 * is expected to have discovered the UUID from the GitHub comment
 * or from a separate call to the web dashboard.</p>
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final PullRequestRepository pullRequestRepository;
    private final FindingRepository findingRepository;

    public ReviewController(PullRequestRepository pullRequestRepository,
                            FindingRepository findingRepository) {
        this.pullRequestRepository = pullRequestRepository;
        this.findingRepository = findingRepository;
    }

    /**
     * GET /api/reviews/{prId} — full review result for a single PR.
     *
     * <p>Ownership check: the PR's repository owner must match the
     * authenticated user. Returns 404 (not 403) so we don't leak
     * the existence of out-of-scope PRs.</p>
     */
    @GetMapping("/{prId}")
    public ResponseEntity<ReviewResponse> getReview(@PathVariable("prId") UUID prId) {
        PullRequestEntity pr = pullRequestRepository.findById(prId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "Review " + prId + " not found"));

        // Ownership check — mirror ScanController.recordAction() pattern.
        UserDetails caller = getCurrentUser();
        if (!isOwnedBy(pr, caller)) {
            throw new jakarta.persistence.EntityNotFoundException(
                    "Review " + prId + " not found");
        }

        List<ReviewResponse.FindingDto> findings = findingRepository
                .findAllByPullRequestId(prId)
                .stream()
                .map(f -> new ReviewResponse.FindingDto(
                        f.getId(),
                        f.getFilePath(),
                        f.getLineStart(),
                        f.getLineEnd(),
                        f.getAntiPattern(),
                        f.getCategory(),
                        f.getSeverity(),
                        f.getConfidence(),
                        f.getExplanation(),
                        f.getCodeSnippet()
                ))
                .toList();

        var repo = pr.getRepo();
        var owner = repo == null ? null : repo.getOwner();

        ReviewResponse body = new ReviewResponse(
                pr.getId(),
                pr.getGithubPrNumber(),
                pr.getTitle(),
                pr.getAuthorGithub(),
                pr.getStatus(),
                pr.getQualityScore(),
                pr.getCreatedAt(),
                pr.getReviewedAt(),
                pr.getErrorMessage(),
                pr.getHeadSha(),
                pr.getGithubPrUrl(),
                repo == null ? null : repo.getId(),
                repo == null ? null : repo.getFullName(),
                owner == null ? null : owner.getGithubUsername(),
                owner == null ? null : owner.getAvatarUrl(),
                findings
        );
        return ResponseEntity.ok(body);
    }

    // --- helpers -----------------------------------------------------------

    /**
     * Extract the current user from the security context.
     * Returns {@code null} for unauthenticated callers (defence in depth —
     * the filter chain should already have rejected them).
     */
    private static UserDetails getCurrentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserDetails userDetails)) {
            return null;
        }
        return userDetails;
    }

    /**
     * Returns true when the PR's repository owner matches the caller.
     * Uses 404 semantics (not 403) so we don't leak PR existence to
     * unauthorized callers.
     */
    private static boolean isOwnedBy(PullRequestEntity pr, UserDetails caller) {
        if (caller == null || caller.getUsername() == null) return false;
        Repository repo = pr.getRepo();
        if (repo == null || repo.getOwner() == null) return false;
        String ownerLogin = repo.getOwner().getGithubUsername();
        return ownerLogin != null && ownerLogin.equals(caller.getUsername());
    }
}