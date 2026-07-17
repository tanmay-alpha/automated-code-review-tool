package com.codelens.service;

import com.codelens.dto.MlFinding;
import com.codelens.dto.ReviewResult;
import com.codelens.entity.PullRequestEntity;
import com.codelens.entity.Repository;
import com.codelens.entity.User;
import com.codelens.repository.PullRequestRepository;
import com.codelens.repository.RepositoryRepository;
import com.codelens.repository.UserRepository;
import com.codelens.security.EncryptionService;
import com.codelens.webhook.GitHubWebhookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Orchestrator: runs the full review flow off the request thread.
 *
 * <p>Invoked from {@link com.codelens.controller.WebhookController} as
 * {@code @Async("taskExecutor")}. The controller returns 200 to GitHub
 * immediately; this method runs in the background pool.</p>
 *
 * <p>The actual review work (ML call + DB writes) is delegated to
 * {@link ReviewService}. This class is responsible for:</p>
 * <ul>
 *   <li>Idempotency check (don't re-review the same head SHA)</li>
 *   <li>GitHub token decryption + diff fetch</li>
 *   <li>Posting the GitHub comment</li>
 *   <li>Top-level error handling — failures are persisted as
 *       {@code status="failed"} + {@code error_message}, never re-thrown</li>
 * </ul>
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    static final String STATUS_PROCESSING = "processing";
    static final String STATUS_REVIEWED = "reviewed";
    static final String STATUS_FAILED = "failed";

    private final RepositoryRepository repositoryRepository;
    private final UserRepository userRepository;
    private final PullRequestRepository pullRequestRepository;
    private final EncryptionService encryptionService;
    private final GitHubService githubService;
    private final ReviewService reviewService;

    public WebhookService(RepositoryRepository repositoryRepository,
                          UserRepository userRepository,
                          PullRequestRepository pullRequestRepository,
                          EncryptionService encryptionService,
                          GitHubService githubService,
                          ReviewService reviewService) {
        this.repositoryRepository = repositoryRepository;
        this.userRepository = userRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.encryptionService = encryptionService;
        this.githubService = githubService;
        this.reviewService = reviewService;
    }

    @Async("taskExecutor")
    @Transactional
    public void processAsync(GitHubWebhookEvent event, String repoGithubId) {
        Long githubId;
        try {
            githubId = Long.parseLong(repoGithubId);
        } catch (NumberFormatException ex) {
            log.warn("Webhook called with non-numeric repo id: {}", repoGithubId);
            return;
        }
        if (githubId == null || event.pullRequest() == null) {
            log.warn("Webhook processAsync called with missing fields");
            return;
        }

        Repository repo;
        PullRequestEntity pr;
        try {
            repo = repositoryRepository.findByGithubId(githubId).orElse(null);
            if (repo == null) {
                log.info("Webhook for unknown repo {}", repoGithubId);
                return;
            }

            pr = upsertPullRequest(repo, event);

            // Idempotency: if we've already reviewed this PR's current head SHA,
            // don't redo the work.
            String incomingSha = event.pullRequest().headSha();
            if (STATUS_REVIEWED.equals(pr.getStatus())
                    && incomingSha != null
                    && incomingSha.equals(pr.getHeadSha())) {
                log.info("PR #{} already reviewed for head {}; skipping",
                        pr.getGithubPrNumber(), incomingSha);
                return;
            }

            User owner = repo.getOwner() == null
                    ? userRepository.findById(repo.getOwner() == null ? null : repo.getOwner().getId()).orElse(null)
                    : repo.getOwner();
            if (owner == null) {
                log.warn("Repository {} has no owner; cannot fetch GitHub token", repo.getFullName());
            }
            String decryptedToken = (owner == null || owner.getAccessToken() == null)
                    ? null
                    : encryptionService.decrypt(owner.getAccessToken());

            String diff = (decryptedToken == null)
                    ? ""
                    : githubService.getFileDiff(decryptedToken, repo.getFullName(), pr.getGithubPrNumber());

            // -- Delegate the ML + DB writes to ReviewService ----------------
            ReviewResult result = reviewService.orchestrateReview(pr, diff);

            if (!result.success()) {
                // ReviewService already marked PR as failed; no comment to post.
                return;
            }

            // -- Post the GitHub comment -------------------------------------
            String markdown = reviewService.formatGithubComment(
                    result.findings(), result.qualityScore());

            if (decryptedToken != null) {
                try {
                    githubService.postPrComment(decryptedToken, repo.getFullName(),
                            pr.getGithubPrNumber(), markdown);
                } catch (Exception ex) {
                    log.warn("Failed to post GitHub PR comment for PR #{}: {}",
                            pr.getGithubPrNumber(), ex.getMessage());
                }
            }

        } catch (Exception ex) {
            log.error("Webhook processing failed for repo {}: {}", repoGithubId, ex.getMessage(), ex);
            markFailed(githubId, event);
        }
    }

    private PullRequestEntity upsertPullRequest(Repository repo, GitHubWebhookEvent event) {
        int prNumber = event.pullRequest().number();
        Optional<PullRequestEntity> existing =
                pullRequestRepository.findByRepoAndGithubPrNumber(repo, prNumber);
        PullRequestEntity pr = existing.orElseGet(() -> PullRequestEntity.builder()
                .repo(repo)
                .githubPrNumber(prNumber)
                .status(STATUS_PROCESSING)
                .build());
        pr.setTitle(event.pullRequest().title());
        if (event.pullRequest().user() != null) {
            pr.setAuthorGithub(event.pullRequest().user().login());
        }
        pr.setHeadSha(event.pullRequest().headSha());
        pr.setGithubPrUrl(event.pullRequest().htmlUrl());
        pr.setStatus(STATUS_PROCESSING);
        return pullRequestRepository.save(pr);
    }

    private void markFailed(Long githubId, GitHubWebhookEvent event) {
        try {
            Repository repo = githubId == null ? null
                    : repositoryRepository.findByGithubId(githubId).orElse(null);
            if (repo == null || event.pullRequest() == null) return;
            Optional<PullRequestEntity> existing =
                    pullRequestRepository.findByRepoAndGithubPrNumber(repo, event.pullRequest().number());
            existing.ifPresent(pr -> {
                pr.setStatus(STATUS_FAILED);
                pr.setErrorMessage("processing failed; see server logs");
                pullRequestRepository.save(pr);
            });
        } catch (Exception inner) {
            log.error("Failed to mark PR as failed", inner);
        }
    }

    /**
     * Build the GitHub PR comment markdown. Public-static so
     * {@link ReviewService} can reuse the exact same format.
     */
    static String buildComment(List<MlFinding> findings, BigDecimal quality,
                               int critical, int major, int minor) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 🔍 CodeLens Review\n\n");
        String emoji = quality == null ? "❓"
                : quality.compareTo(BigDecimal.valueOf(80)) >= 0 ? "✅"
                : quality.compareTo(BigDecimal.valueOf(60)) >= 0 ? "⚠️" : "❌";
        sb.append("**Quality Score: ")
                .append(quality == null ? "?" : quality.toPlainString())
                .append("/100** ")
                .append(emoji)
                .append("\n\n");

        if (findings == null || findings.isEmpty()) {
            sb.append("✅ No anti-patterns detected. Clean code!\n");
        } else {
            sb.append("### Findings (")
                    .append(critical).append(" critical · ")
                    .append(major).append(" major · ")
                    .append(minor).append(" minor)\n\n");
            sb.append("| Severity | Pattern | File | Lines | Confidence |\n");
            sb.append("|----------|---------|------|-------|------------|\n");
            for (MlFinding f : findings) {
                String sev = f.severity() == null ? "minor" : f.severity().toLowerCase(Locale.ROOT);
                String icon = switch (sev) {
                    case "critical" -> "🔴 Critical";
                    case "major" -> "🟠 Major";
                    default -> "🟡 Minor";
                };
                String lines = (f.lineStart() == null ? "?" : f.lineStart().toString())
                        + (f.lineEnd() == null || f.lineEnd().equals(f.lineStart())
                            ? ""
                            : "-" + f.lineEnd());
                String conf = f.confidence() == null
                        ? "?"
                        : f.confidence().multiply(BigDecimal.valueOf(100))
                                .setScale(0, RoundingMode.HALF_UP) + "%";
                sb.append("| ").append(icon)
                        .append(" | ").append(nullToDash(f.antiPattern()))
                        .append(" | `n/a`")
                        .append(" | ").append(lines)
                        .append(" | ").append(conf)
                        .append(" |\n");
            }
        }
        sb.append("\n---\n");
        sb.append("*Powered by [CodeLens](https://github.com/tanmay-alpha/codelens) — Semantic code review engine*\n");
        return sb.toString();
    }

    private static String nullToDash(String s) { return s == null ? "-" : s; }
}