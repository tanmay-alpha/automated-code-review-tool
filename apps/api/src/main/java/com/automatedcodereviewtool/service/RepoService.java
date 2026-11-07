package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.entity.PullRequestEntity;
import com.automatedcodereviewtool.entity.QualityMetric;
import com.automatedcodereviewtool.entity.Repository;
import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.exception.ConnectRepoException;
import com.automatedcodereviewtool.repository.FindingRepository;
import com.automatedcodereviewtool.repository.PullRequestRepository;
import com.automatedcodereviewtool.repository.QualityMetricRepository;
import com.automatedcodereviewtool.repository.RepositoryRepository;
import com.automatedcodereviewtool.repository.UserRepository;
import com.automatedcodereviewtool.security.EncryptionService;
import com.automatedcodereviewtool.config.WebhookConfig;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for repo connect / disconnect / list / PR history /
 * quality trend.
 *
 * <p>Every read of a single repo goes through {@link #requireOwned}, which
 * returns 404 (not 403) for repos the caller does not own so their
 * existence is not leaked.</p>
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
    private final FindingRepository findingRepository;
    private final GitHubService gitHubService;
    private final EncryptionService encryptionService;
    private final String webhookCallbackUrl;
    private final SecureRandom secureRandom = new SecureRandom();

    public RepoService(RepositoryRepository repositoryRepository,
                       UserRepository userRepository,
                       PullRequestRepository pullRequestRepository,
                       QualityMetricRepository qualityMetricRepository,
                       FindingRepository findingRepository,
                       GitHubService gitHubService,
                       EncryptionService encryptionService,
                       WebhookConfig webhookConfig) {
        this.repositoryRepository = repositoryRepository;
        this.userRepository = userRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.qualityMetricRepository = qualityMetricRepository;
        this.findingRepository = findingRepository;
        this.gitHubService = gitHubService;
        this.encryptionService = encryptionService;
        this.webhookCallbackUrl = webhookConfig.getCallbackUrl();
    }

    // -------------------------------------------------------------------
    // connect
    // -------------------------------------------------------------------

    /**
     * Connect a GitHub repo to the current user.
     *
     * <ol>
     *   <li>Look up the user and confirm they have a GitHub token.</li>
     *   <li>Resolve {@code owner/repo} → numeric ID + visibility via GitHub
     *       (a repo the caller cannot see is rejected).</li>
     *   <li>Reject if the repo is actively connected (by anyone). An
     *       inactive row left by a disconnect is reused; if a different
     *       account disconnected it, ownership moves to the caller (the
     *       webhook install below proves admin access).</li>
     *   <li>Generate a webhook secret, install the webhook on GitHub, then
     *       save the repo with the secret encrypted.</li>
     * </ol>
     *
     * <p>If GitHub rejects the webhook install nothing is written. If the
     * DB write fails after the install, the transaction rolls back and the
     * GitHub webhook is left orphaned (its deliveries are ignored because
     * no active repo matches).</p>
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

        User caller = userRepository.findById(owner.getId())
                .orElseThrow(() -> new EntityNotFoundException("User " + owner.getId() + " not found"));

        if (caller.getAccessToken() == null || caller.getAccessToken().isBlank()) {
            throw new ConnectRepoException("User has no GitHub access token; re-authenticate");
        }
        // users.access_token is AES-encrypted at rest; GitHub needs the plaintext.
        String githubToken = encryptionService.decrypt(caller.getAccessToken());

        var ghRepo = gitHubService.getRepository(githubToken, repoOwner, repoName);
        if (ghRepo == null || ghRepo.get("id") == null) {
            throw new ConnectRepoException("Repository not found on GitHub or not visible to caller");
        }
        Long ghId = ((Number) ghRepo.get("id")).longValue();
        boolean isPrivate = Boolean.TRUE.equals(ghRepo.get("private"));

        // github_id is unique, so a disconnected (inactive) row must be reused.
        Optional<Repository> existing = repositoryRepository.findByGithubId(ghId);
        if (existing.isPresent() && existing.get().isActive()) {
            throw new ConnectRepoException("This repository is already connected to an automated-code-review-tool account");
        }

        String plaintextSecret = generateSecret();
        Long webhookId;
        try {
            webhookId = gitHubService.installWebhook(
                    githubToken, fullName, webhookCallbackUrl, plaintextSecret);
        } catch (RuntimeException ex) {
            throw new ConnectRepoException(
                    "Failed to install webhook on GitHub: " + ex.getMessage(), ex);
        }
        if (webhookId == null) {
            throw new ConnectRepoException("GitHub did not return a webhook id");
        }

        Repository repo = existing.orElseGet(() -> Repository.builder()
                .githubId(ghId)
                .build());
        if (repo.getOwner() != null && !repo.getOwner().getId().equals(caller.getId())) {
            log.info("Reassigning inactive repo {} from user {} to {}",
                    fullName, repo.getOwner().getId(), caller.getId());
        }
        repo.setOwner(caller);
        repo.setFullName(fullName);
        repo.setDescription((String) ghRepo.get("description"));
        repo.setPrivate(isPrivate);
        repo.setWebhookId(webhookId);
        repo.setWebhookSecret(encryptionService.encrypt(plaintextSecret));
        repo.setActive(true);
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
     * fails we still mark the repo inactive, and any further deliveries
     * for it are acknowledged and ignored by the webhook handler.
     */
    @Transactional
    public void disconnect(User caller, UUID repoId) {
        Repository repo = requireOwned(caller, repoId);

        try {
            if (repo.getWebhookId() != null) {
                // The repo's owner is the caller (requireOwned); its token is encrypted at rest.
                gitHubService.deleteWebhook(
                        encryptionService.decrypt(repo.getOwner().getAccessToken()),
                        repo.getFullName(), repo.getWebhookId());
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
     * Returns the caller's active repos with their dashboard aggregates.
     * Issues three small queries per repo (PR count, latest review, 30-day
     * metrics) — fine for the handful of repos a user connects.
     */
    @Transactional(readOnly = true)
    public List<RepoSummary> listForOwner(User caller) {
        return repositoryRepository.findAllByOwnerAndIsActiveTrue(caller).stream()
                .map(this::summarize)
                .toList();
    }

    /** Same aggregates as {@link #listForOwner} for a single owned repo. */
    @Transactional(readOnly = true)
    public RepoSummary getForOwner(User caller, UUID repoId) {
        return summarize(requireOwned(caller, repoId));
    }

    /**
     * Load a repo and verify the caller owns it. Throws
     * {@link EntityNotFoundException} (→ 404) for missing and foreign repos alike.
     */
    @Transactional(readOnly = true)
    public Repository requireOwned(User caller, UUID repoId) {
        Repository repo = repositoryRepository.findById(repoId)
                .orElseThrow(() -> new EntityNotFoundException("Repo " + repoId + " not found"));
        if (!repo.getOwner().getId().equals(caller.getId())) {
            throw new EntityNotFoundException("Repo " + repoId + " not found");
        }
        return repo;
    }

    private RepoSummary summarize(Repository repo) {
        return new RepoSummary(
                repo,
                pullRequestRepository.countByRepoAndStatus(repo, "reviewed"),
                latestReviewAt(repo),
                averageScore(repo));
    }

    private Instant latestReviewAt(Repository repo) {
        return pullRequestRepository.findFirstByRepoOrderByReviewedAtDesc(repo)
                .map(PullRequestEntity::getReviewedAt)
                .orElse(null);
    }

    /** Mean of the daily averages over the last 30 days (null when no reviews). */
    private BigDecimal averageScore(Repository repo) {
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
        Repository repo = requireOwned(caller, repoId);
        PageRequest pageReq = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return pullRequestRepository.findAllByRepo(repo, pageReq);
    }

    // -------------------------------------------------------------------
    // quality trend
    // -------------------------------------------------------------------

    /** Daily metrics for the last {@code days} UTC days (clamped to 1..365), today included. */
    @Transactional(readOnly = true)
    public List<QualityMetric> qualityTrend(Repository repo, int days) {
        return qualityMetricRepository.findRange(repo, windowStart(days), LocalDate.now(ZoneOffset.UTC));
    }

    /** Most frequent anti-pattern among findings in the same window as {@link #qualityTrend}. */
    @Transactional(readOnly = true)
    public String topAntiPattern(Repository repo, int days) {
        Instant since = windowStart(days).atStartOfDay(ZoneOffset.UTC).toInstant();
        return findingRepository.findTopAntiPatterns(repo, since).stream()
                .findFirst()
                .map(FindingRepository.AntiPatternCount::getPattern)
                .orElse(null);
    }

    private static LocalDate windowStart(int days) {
        int window = Math.max(1, Math.min(days, 365));
        return LocalDate.now(ZoneOffset.UTC).minusDays(window - 1L);
    }

    // -------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------

    /** 32 random hex chars = 128 bits of entropy. */
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
