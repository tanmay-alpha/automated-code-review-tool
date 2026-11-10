package com.automatedcodereviewtool.controller;

import com.automatedcodereviewtool.dto.ReviewRunResponse;
import com.automatedcodereviewtool.entity.ProcessedWebhook;
import com.automatedcodereviewtool.entity.PullRequestEntity;
import com.automatedcodereviewtool.entity.Repository;
import com.automatedcodereviewtool.entity.ReviewPublication;
import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.repository.ProcessedWebhookRepository;
import com.automatedcodereviewtool.repository.PullRequestRepository;
import com.automatedcodereviewtool.repository.ReviewPublicationRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Review-run history of a PR: each webhook delivery, its retry state and
 * the GitHub publication it produced, newest first (at most 50).
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewRunController {

    private final PullRequestRepository pullRequestRepository;
    private final ProcessedWebhookRepository processedWebhookRepository;
    private final ReviewPublicationRepository publicationRepository;

    public ReviewRunController(PullRequestRepository pullRequestRepository,
                               ProcessedWebhookRepository processedWebhookRepository,
                               ReviewPublicationRepository publicationRepository) {
        this.pullRequestRepository = pullRequestRepository;
        this.processedWebhookRepository = processedWebhookRepository;
        this.publicationRepository = publicationRepository;
    }

    /** 404 (not 403) for PRs outside the caller's repositories, like {@link ReviewController}. */
    @GetMapping("/{prId}/runs")
    public ResponseEntity<List<ReviewRunResponse>> getRuns(@PathVariable("prId") UUID prId,
                                                           @AuthenticationPrincipal User caller) {
        PullRequestEntity pr = pullRequestRepository.findWithRepoAndOwnerById(prId)
                .filter(found -> isOwnedBy(found, caller))
                .orElseThrow(() -> new EntityNotFoundException("Review " + prId + " not found"));

        List<ProcessedWebhook> runs = processedWebhookRepository
                .findTop50ByRepoIdAndGithubPrNumberOrderByProcessedAtDesc(pr.getRepo().getId(), pr.getGithubPrNumber());
        Map<String, ReviewPublication> publications = runs.isEmpty() ? Map.of()
                : publicationRepository.findAllByDeliveryIdIn(
                                runs.stream().map(ProcessedWebhook::getDeliveryId).toList())
                        .stream()
                        .collect(Collectors.toMap(ReviewPublication::getDeliveryId, Function.identity()));

        return ResponseEntity.ok(runs.stream()
                .map(run -> {
                    ReviewPublication p = publications.get(run.getDeliveryId());
                    return new ReviewRunResponse(
                            run.getDeliveryId(),
                            run.getHeadSha(),
                            run.getAction(),
                            run.getStatus(),
                            run.getAttemptCount(),
                            run.getLastError(),
                            run.getProcessedAt(),
                            run.getCompletedAt(),
                            p == null ? null : new ReviewRunResponse.Publication(
                                    p.getStatus(), p.getCommentUrl(), p.getPublishedHeadSha()));
                })
                .toList());
    }

    private static boolean isOwnedBy(PullRequestEntity pr, User caller) {
        if (caller == null || caller.getId() == null) return false;
        Repository repo = pr.getRepo();
        if (repo == null || repo.getOwner() == null || repo.getOwner().getId() == null) return false;
        return caller.getId().equals(repo.getOwner().getId());
    }
}
