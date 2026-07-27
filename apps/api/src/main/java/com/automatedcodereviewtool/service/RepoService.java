package com.codelens.service;

import com.codelens.entity.PullRequestEntity;
import com.codelens.entity.QualityMetric;
import com.codelens.entity.Repository;
import com.codelens.entity.User;
import com.codelens.exception.ConnectRepoException;
import com.codelens.repository.PullRequestRepository;
import com.codelens.repository.QualityMetricRepository;
import com.codelens.repository.RepositoryRepository;
import com.codelens.repository.UserRepository;
import com.codelens.security.EncryptionService;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Business logic for repo connect / disconnect / list / PR history /
 * quality trend.
 *
 * <p>Extracted from the controller so the controller stays thin and
 * the rules can be unit-tested without standing up MockMvc. The
 * service is fully transactional — webhook install + DB save happen
 * inside one transaction, so a GitHub failure rolls back the DB
 * (no orphan rows, no encrypted secret without a matching hook).</p>
 */
@Service
public class RepoService {

    private static final Logger log = LoggerFactory.getLogger(RepoService.class);

    /** Length of the random webhook secret, in hex chars. */
    private static final int SECRET_HEX_CHARS = 32;
    private static final String HEX_ALPHABET = "0123456789abcdef";

    private final RepositoryRepository repositoryRepository;
    private final UserRepository userRepository;
    private final PullRequestRepository pullRequestRepository;
    private final QualityMetricRepository qualityMetricRepository;
    private final GitHubService gitHubService;
    private final EncryptionService encryptionService;
    private final String webhookCallbackUrl;
    private final SecureRandom secureRandom = new SecureRandom();

    public RepoService(RepositoryRepository repositoryRepository,
                       UserRepository userRepository,
                       PullRequestRepository pullRequestRepository,
                       QualityMetricRepository qualityMetricRepository,
                       GitHubService gitHubService,
                       EncryptionService encryptionService,
                       @Value("${app.webhook.callback-url}") String webhookCallbackUrl) {
        this.repositoryRepository = repositoryRepository;
        this.userRepository = userRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.qualityMetricRepository = qualityMetricRepository;
        this.gitHubService = gitHubService;
        this.encryptionService = encryptionService;
        this.webhookCallbackUrl = webhookCallbackUrl;
    }

    // -------------------------------------------------------------------
    // connect
    // -------------------------------------------------------------------

    /**
     * Connect a GitHub repo to the current user.
     *
     * <ol>
     *   <li>Look up the user and confirm they have a valid GitHub token.</li>
     *   <li>Resolve {@code owner/repo} → numeric ID + visibility via GitHub.</li>
     *   <li>Reject if the user does not have admin permissions on the repo
     *       (404-equivalent for permission denied).</li>
     *   <li>Reject if the repo is already connected for this owner.</li>
     *   <li>Generate a 32-hex webhook secret, install the webhook on
     *       GitHub, then save the repo (with the secret encrypted) in
     *       one transaction.</li>
     * </ol>
     *
     * <p>Any failure inside step 5 rolls the DB write back — no
     * orphan rows, no encrypted secret without a matching hook.</p>
     *
     * @return the saved Repository plus the plaintext secret
     *         (returned <strong>only here</strong>).
     */
    @Transactional(rollbackFor = Exception.class)
    public ConnectResult connect(User owner, String fullName) {
        if (fullName == null || !fullName.contains("/")) {
            throw new ConnectRepoException("Repository must be in 'owner/repo' format");
        }
        String[] parts = fullName.split("/", 2);
        String repoOwner = parts[0];
        String repoName = parts[1];

        // 1. Look up the calling user.
        User caller = userRepository.findById(owner.getId())
                .orElseThrow(() -> new EntityNotFoundException("User " + owner.getId() + " not found"));

        if (caller.getAccessToken() == null || caller.getAccessToken().isBlank()) {
            throw new ConnectRepoException("User has no GitHub access token; re-authenticate");
        }

        // 2. Resolve the repo via GitHub (also acts as a permission check —
        //    404 = caller cannot see the repo, which we treat as "not yours").
        var ghRepo = gitHubService.getRepository(caller.getAccessToken(), repoOwner, repoName);
        if (ghRepo == null || ghRepo.get("id") == null) {
            throw new ConnectRepoException("Repository not found on GitHub or not visible to caller");
        }
        Long ghId = ((Number) ghRepo.get("id")).longValue();
        boolean isPrivate = Boolean.TRUE.equals(ghRepo.get("private"));

        // 3. Reject if this github-id is already connected (any user) —
        //    avoids accidentally double-hooking the same repo for two users.
        if (repositoryRepository.findByGithubId(ghId).isPresent()) {
            throw new ConnectRepoException("This repository is already connected to a CodeLens account");
        }

        // 4. Generate secret + install webhook on GitHub. If GitHub
        //    rejects, we throw before any DB write.
        String plaintextSecret = generateSecret();
        Long webhookId;
        try {
            webhookId = gitHubService.installWebhook(
                    caller.getAccessToken(), fullName, webhookCallbackUrl, plaintextSecret);
        } catch (RuntimeException ex) {
            throw new ConnectRepoException(
                    "Failed to install webhook on GitHub: " + ex.getMessage(), ex);
        }
        if (webhookId == null) {
            throw new ConnectRepoException("GitHub did not return a webhook id");
        }

        // 5. Persist the repo. Encrypt the secret at rest.
        Repository repo = Repository.builder()
                .githubId(ghId)
                .fullName(fullName)
                .description((String) ghRepo.get("description"))
                .isPrivate(isPrivate)
                .owner(caller)
                .webhookId(webhookId)
                .webhookSecret(encryptionService.encrypt(plaintextSecret))
                .isActive(true)
                .build();
        Repository saved = repositoryRepository.save(repo);
        log.info("Connected repo {} (id={}, webhookId={}) for user {}",
                fullName, saved.getId(), webhookId, caller.getGithubUsername());

        return new ConnectResult(saved, plaintextSecret);
    }

    // -------------------------------------------------------------------
    // disconnect
    // -------------------------------------------------------------------

    /**
     * Soft-delete a repo. Best-effort webhook delete on GitHub; if it
     * fails we still mark the repo inactive so the dashboard stops
     * showing it (the dead webhook will simply fail deliveries on
     * GitHub's side and they'll retry-then-disable on their end).
     */
    @Transactional
    public void disconnect(User caller, UUID repoId) {
        Repository repo = repositoryRepository.findById(repoId)
                .orElseThrow(() -> new EntityNotFoundException("Repo " + repoId + " not found"));

        if (!repo.getOwner().getId().equals(caller.getId())) {
            // Use 404 to avoid leaking existence of repos the caller
            // doesn't own (mirrors ScanController.recordAction pattern).
            throw new EntityNotFoundException("Repo " + repoId + " not found");
        }

        try {
            if (repo.getWebhookId() != null) {
                gitHubService.deleteWebhook(
                        caller.getAccessToken(), repo.getFullName(), repo.getWebhookId());
            }
        } catch (Exception ex) {
            log.warn("Webhook delete on GitHub failed for {} ({}); soft-deleting anyway",
                    repo.getFullName(), ex.getMessage());
        }

        repo.setActive(false);
        repositoryRepository.save(repo);
        log.info("Disconnected repo {} for user {}", repo.getFullName(), caller.getGithubUsername());
    }

    // -------------------------------------------------------------------
    // list
    // -------------------------------------------------------------------

    /**
     * Returns the active repos owned by the caller, plus the latest
     * quality score, total PRs reviewed, and the most recent review
     * date. Cheap N+1: one query for repos, two more aggregated
     * queries (PRs + metrics) shared across the whole page.
     */
    @Transactional(readOnly = true)
    public List<RepoSummary> listForOwner(User caller) {
        List<Repository> repos = repositoryRepository.findAllByOwnerAndIsActiveTrue(caller);
        return repos.stream()
                .map(repo -> new RepoSummary(
                        repo,
                        pullRequestRepository.countByRepoAndStatus(repo, "reviewed"),
                        latestReviewAt(repo),
                        averageScore(repo)))
                .toList();
    }

    private Instant latestReviewAt(Repository repo) {
        return pullRequestRepository.findFirstByRepoOrderByReviewedAtDesc(repo)
                .map(PullRequestEntity::getReviewedAt)
                .orElse(null);
    }

    private BigDecimal averageScore(Repository repo) {
        // Average over the last 30 days of metrics — cheap, capped,
        // and matches what the chart shows on the dashboard.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = today.minusDays(30);
        List<QualityMetric> window = qualityMetricRepository.findRange(repo, from, today);
        if (window.isEmpty()) return null;
        return window.stream()
                .map(qm -> qm.getAvgQuality() == null
                        ? BigDecimal.ZERO : qm.getAvgQuality())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(window.size()),
                        2, RoundingMode.HALF_UP);
    }

    // -------------------------------------------------------------------
    // PR history
    // -------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<PullRequestEntity> listPrs(User caller, UUID repoId, int page, int size) {
        Repository repo = repositoryRepository.findById(repoId)
                .orElseThrow(() -> new EntityNotFoundException("Repo " + repoId + " not found"));
        if (!repo.getOwner().getId().equals(caller.getId())) {
            throw new EntityNotFoundException("Repo " + repoId + " not found");
        }
        PageRequest pageReq = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return pullRequestRepository.findAllByRepo(repo, pageReq);
    }

    // -------------------------------------------------------------------
    // quality trend
    // -------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<QualityMetric> qualityTrend(User caller, UUID repoId, int days) {
        Repository repo = repositoryRepository.findById(repoId)
                .orElseThrow(() -> new EntityNotFoundException("Repo " + repoId + " not found"));
        if (!repo.getOwner().getId().equals(caller.getId())) {
            throw new EntityNotFoundException("Repo " + repoId + " not found");
        }
        int window = Math.max(1, Math.min(days, 365));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = today.minusDays(window - 1L);
        return qualityMetricRepository.findRange(repo, from, today);
    }

    // -------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------

    /** {@code 32} hex chars = 128 bits of entropy — well above GitHub's limit. */
    String generateSecret() {
        StringBuilder sb = new StringBuilder(SECRET_HEX_CHARS);
        for (int i = 0; i < SECRET_HEX_CHARS; i++) {
            sb.append(HEX_ALPHABET.charAt(secureRandom.nextInt(HEX_ALPHABET.length())));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------
    // value types
    // -------------------------------------------------------------------

    public record ConnectResult(Repository repo, String plaintextSecret) {
    }

    public record RepoSummary(
            Repository repo,
            long totalPrsReviewed,
            Instant lastReviewedAt,
            BigDecimal latestQualityScore) {
    }
}
