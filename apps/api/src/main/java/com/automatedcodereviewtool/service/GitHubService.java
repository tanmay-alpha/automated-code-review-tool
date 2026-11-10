package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.config.GitHubConfig;
import com.automatedcodereviewtool.dto.GitHubTokenResponse;
import com.automatedcodereviewtool.dto.GitHubUserInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Thin client for the GitHub REST API and OAuth dance.
 *
 * <p>Uses two {@link WebClient} instances — one for {@code github.com}
 * (OAuth token exchange, webhooks) and one for {@code api.github.com}
 * (user, diff, comment) — to keep base URLs out of every call.</p>
 */
@Service
@EnableConfigurationProperties(GitHubConfig.class)
public class GitHubService {

    private static final Logger log = LoggerFactory.getLogger(GitHubService.class);

    private static final String OAUTH_AUTHORIZE_URL = "https://github.com/login/oauth/authorize";
    private static final String OAUTH_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String API_BASE = "https://api.github.com";

    private final WebClient oauthClient;
    private final WebClient apiClient;
    private final GitHubConfig config;

    /** TCP connect timeout. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** Netty response timeout: maximum wait for response data after the request is sent. */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);
    /**
     * Upper bound per call (connect + headers + full body). The Netty response
     * timeout is a read-idle timer, so a slowly trickling body could otherwise
     * hold a webhook or request thread indefinitely.
     */
    private static final Duration CALL_DEADLINE = Duration.ofSeconds(45);

    private static final HttpClient HTTP_CLIENT = HttpClient.create()
            .responseTimeout(RESPONSE_TIMEOUT)
            .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis());

    /** Large enough for a page of long comments; larger diffs are refused as too large. */
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    /** Page size for list endpoints, bounded so a page of 65 KB comments fits in memory limits. */
    static final int PAGE_SIZE = 50;
    /** Pagination cap per lookup ({@code PAGE_SIZE × MAX_PAGES} items). */
    static final int MAX_PAGES = 40;
    /** Longest rate-limit wait honoured before retrying anyway. */
    static final Duration MAX_RATE_LIMIT_WAIT = Duration.ofHours(1);
    /** GitHub's documented minimum wait for a secondary rate limit sent without headers. */
    static final Duration DEFAULT_RATE_LIMIT_WAIT = Duration.ofSeconds(60);

    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() { };

    public GitHubService(WebClient.Builder builder, GitHubConfig config) {
        this(builder, config, API_BASE);
    }

    @Autowired
    public GitHubService(WebClient.Builder builder, GitHubConfig config,
                         @Value("${app.github.api-base-url:" + API_BASE + "}") String apiBaseUrl) {
        WebClient.Builder timeoutBuilder = builder
                .clientConnector(new ReactorClientHttpConnector(HTTP_CLIENT))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES));
        this.oauthClient = timeoutBuilder.baseUrl("https://github.com").build();
        this.apiClient = timeoutBuilder.baseUrl(apiBaseUrl).build();
        this.config = config;
    }

    /**
     * Build the URL the browser should be redirected to in order to start
     * the OAuth flow. We request {@code read:user} (for the user's
     * profile) and {@code repo} (to install webhooks + post PR comments).
     */
    public String getOAuthRedirectUrl() {
        return UriComponentsBuilder.fromHttpUrl(OAUTH_AUTHORIZE_URL)
                .queryParam("client_id", config.getClientId())
                .queryParam("redirect_uri", URLEncoder.encode(config.getOauthRedirectUri(), StandardCharsets.UTF_8))
                .queryParam("scope", URLEncoder.encode("read:user repo", StandardCharsets.UTF_8))
                .build()
                .toUriString();
    }

    /**
     * Server-to-server POST exchanging the OAuth {@code code} for an
     * access token. Note GitHub returns 200 even on bad code — the
     * presence of {@code access_token} indicates success.
     */
    public GitHubTokenResponse exchangeCodeForToken(String code) {
        return oauthClient.post()
                .uri(URI.create(OAUTH_TOKEN_URL))
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(Map.of(
                        "client_id", config.getClientId(),
                        "client_secret", config.getClientSecret(),
                        "code", code,
                        "redirect_uri", config.getOauthRedirectUri()))
                .retrieve()
                .bodyToMono(GitHubTokenResponse.class)
                .block(CALL_DEADLINE);
    }

    public GitHubUserInfo getCurrentUser(String accessToken) {
        return apiClient.get()
                .uri("/user")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(GitHubUserInfo.class)
                .block(CALL_DEADLINE);
    }

    /**
     * Look up a single repo by {@code owner/repo}. Returns the raw JSON
     * as a Map so the caller can read any field it needs (id, private,
     * description, default_branch, …) without us maintaining a DTO.
     *
     * <p>Used by {@code RepoService.connect} to verify the user has
     * visibility on the repo before installing a webhook. A 404 from
     * GitHub is propagated as a 5xx/empty body — callers should
     * null-check the return.</p>
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getRepository(String accessToken, String owner, String name) {
        RepoPath repo = RepoPath.of(owner + "/" + name);
        try {
            return apiClient.get()
                    .uri("/repos/{owner}/{name}", repo.owner(), repo.name())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(CALL_DEADLINE);
        } catch (Exception ex) {
            // 404 / 401 / 403 — all manifest as a thrown exception from WebClient
            log.debug("getRepository failed for {}/{}: {}", owner, name, ex.getMessage());
            return null;
        }
    }

    /**
     * Fetch the unified diff for a PR. The {@code .diff} media type asks
     * GitHub to return raw diff text rather than the JSON metadata.
     * GitHub answers 406/422 when the diff is too large to render.
     */
    public String getFileDiff(String accessToken, String repoFullName, int prNumber) {
        RepoPath repo = RepoPath.of(repoFullName);
        return call("fetch pull request diff", true, () -> apiClient.get()
                .uri("/repos/{owner}/{repo}/pulls/{n}", repo.owner(), repo.name(), prNumber)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header(HttpHeaders.ACCEPT, "application/vnd.github.v3.diff")
                .retrieve()
                .bodyToMono(String.class)
                .block(CALL_DEADLINE));
    }

    /** The PR's current head commit as GitHub sees it right now. */
    public String getPullRequestHeadSha(String accessToken, String repoFullName, int prNumber) {
        RepoPath repo = RepoPath.of(repoFullName);
        Map<?, ?> pr = call("read pull request head", false, () -> apiClient.get()
                .uri("/repos/{owner}/{repo}/pulls/{n}", repo.owner(), repo.name(), prNumber)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .retrieve()
                .bodyToMono(Map.class)
                .block(CALL_DEADLINE));
        Object head = pr == null ? null : pr.get("head");
        Object sha = head instanceof Map<?, ?> h ? h.get("sha") : null;
        if (!(sha instanceof String s) || s.isBlank()) {
            throw new GitHubApiException(GitHubApiException.Kind.TRANSIENT, 200, null,
                    "read pull request head returned no head SHA", null);
        }
        return s;
    }

    /** An issue comment written by this service. */
    public record IssueComment(long id, String htmlUrl) {
    }

    /** One inline review comment on an added (RIGHT-side) line of the PR diff. */
    public record InlineComment(String path, int line, String body) {
    }

    /**
     * The first comment on the PR written by {@code authorGithubId} whose body
     * starts with {@code marker}. Pages through every comment, so the lookup
     * still works on long PR conversations. Matching on the author stops a PR
     * participant from planting the marker to hijack or block the summary.
     */
    public Optional<IssueComment> findIssueComment(String accessToken, String repoFullName, int prNumber,
                                                   String marker, long authorGithubId) {
        RepoPath repo = RepoPath.of(repoFullName);
        return findInPages("list issue comments", accessToken,
                b -> b.path("/repos/{owner}/{repo}/issues/{n}/comments"),
                new Object[]{repo.owner(), repo.name(), prNumber},
                item -> authoredBy(item, authorGithubId) && bodyStartsWith(item, marker))
                .map(GitHubService::toIssueComment);
    }

    public IssueComment createIssueComment(String accessToken, String repoFullName, int prNumber, String body) {
        RepoPath repo = RepoPath.of(repoFullName);
        Map<?, ?> created = call("create issue comment", false, () -> apiClient.post()
                .uri("/repos/{owner}/{repo}/issues/{n}/comments", repo.owner(), repo.name(), prNumber)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("body", body))
                .retrieve()
                .bodyToMono(Map.class)
                .block(CALL_DEADLINE));
        return toIssueComment(created);
    }

    public IssueComment updateIssueComment(String accessToken, String repoFullName, long commentId, String body) {
        RepoPath repo = RepoPath.of(repoFullName);
        Map<?, ?> updated = call("update issue comment", false, () -> apiClient.patch()
                .uri("/repos/{owner}/{repo}/issues/comments/{id}", repo.owner(), repo.name(), commentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("body", body))
                .retrieve()
                .bodyToMono(Map.class)
                .block(CALL_DEADLINE));
        return toIssueComment(updated);
    }

    /** Id of the first PR review by {@code authorGithubId} whose body starts with {@code marker}. */
    public Optional<Long> findReview(String accessToken, String repoFullName, int prNumber,
                                     String marker, long authorGithubId) {
        RepoPath repo = RepoPath.of(repoFullName);
        return findInPages("list pull request reviews", accessToken,
                b -> b.path("/repos/{owner}/{repo}/pulls/{n}/reviews"),
                new Object[]{repo.owner(), repo.name(), prNumber},
                item -> authoredBy(item, authorGithubId) && bodyStartsWith(item, marker))
                .map(item -> requiredId(item, "list pull request reviews"));
    }

    /**
     * Create a {@code COMMENT} review pinned to {@code commitId}, so its
     * comments anchor to the reviewed revision even if the PR moves on.
     */
    public long createReview(String accessToken, String repoFullName, int prNumber, String commitId,
                             String body, List<InlineComment> comments) {
        RepoPath repo = RepoPath.of(repoFullName);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("commit_id", commitId);
        request.put("event", "COMMENT");
        request.put("body", body);
        request.put("comments", comments.stream()
                .map(c -> Map.of("path", c.path(), "line", c.line(), "side", "RIGHT", "body", c.body()))
                .toList());
        Map<?, ?> created = call("create pull request review", false, () -> apiClient.post()
                .uri("/repos/{owner}/{repo}/pulls/{n}/reviews", repo.owner(), repo.name(), prNumber)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .block(CALL_DEADLINE));
        return requiredId(created, "create pull request review");
    }

    /**
     * Install a repo-level webhook that POSTs to {@code webhookUrl} on
     * pull-request events. Returns the GitHub-assigned webhook id.
     */
    public Long installWebhook(String accessToken, String repoFullName,
                               String webhookUrl, String secret) {
        RepoPath repo = RepoPath.of(repoFullName);
        Map<String, Object> body = Map.of(
                "name", "web",
                "active", true,
                "events", List.of("pull_request"),
                "config", Map.of(
                        "url", webhookUrl,
                        "content_type", "json",
                        "secret", secret));
        Map<?, ?> response = call("install webhook", false, () -> apiClient.post()
                .uri("/repos/{owner}/{repo}/hooks", repo.owner(), repo.name())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block(CALL_DEADLINE));
        Object id = response == null ? null : response.get("id");
        return id instanceof Number n ? n.longValue() : null;
    }

    public void deleteWebhook(String accessToken, String repoFullName, Long webhookId) {
        if (webhookId == null) return;
        RepoPath repo = RepoPath.of(repoFullName);
        call("delete webhook", false, () -> apiClient.delete()
                .uri("/repos/{owner}/{repo}/hooks/{id}", repo.owner(), repo.name(), webhookId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .toBodilessEntity()
                .block(CALL_DEADLINE));
    }

    // -----------------------------------------------------------------
    // Pagination
    // -----------------------------------------------------------------

    /**
     * Walk a list endpoint page by page until {@code match} accepts an item
     * or GitHub's {@code Link} header has no {@code rel="next"}. Pages are
     * requested by number against the configured API base, never by
     * following the URL in the header.
     */
    private Optional<Map<String, Object>> findInPages(String operation, String accessToken,
                                                      Function<UriBuilder, UriBuilder> path, Object[] variables,
                                                      Predicate<Map<String, Object>> match) {
        for (int page = 1; page <= MAX_PAGES; page++) {
            int pageNumber = page;
            ResponseEntity<List<Map<String, Object>>> response = call(operation, false, () -> apiClient.get()
                    .uri(b -> path.apply(b)
                            .queryParam("per_page", PAGE_SIZE)
                            .queryParam("page", pageNumber)
                            .build(variables))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .retrieve()
                    .toEntity(JSON_LIST)
                    .block(CALL_DEADLINE));
            List<Map<String, Object>> items = response == null ? null : response.getBody();
            if (items != null) {
                for (Map<String, Object> item : items) {
                    if (match.test(item)) {
                        return Optional.of(item);
                    }
                }
            }
            if (response == null || !hasNextPage(response.getHeaders())) {
                return Optional.empty();
            }
        }
        // An incomplete lookup must never be interpreted as "not found":
        // creating a comment here could duplicate one on a later page.
        throw new GitHubApiException(GitHubApiException.Kind.TRANSIENT, 0, null,
                operation + " exceeded the safe pagination limit; no external write attempted", null);
    }

    static boolean hasNextPage(HttpHeaders headers) {
        for (String link : headers.getOrEmpty(HttpHeaders.LINK)) {
            for (String part : link.split(",")) {
                if (part.replace(" ", "").contains("rel=\"next\"")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean authoredBy(Map<String, Object> item, long githubId) {
        return item.get("user") instanceof Map<?, ?> user
                && user.get("id") instanceof Number id
                && id.longValue() == githubId;
    }

    private static boolean bodyStartsWith(Map<String, Object> item, String marker) {
        return item.get("body") instanceof String body && body.startsWith(marker);
    }

    private static IssueComment toIssueComment(Map<?, ?> json) {
        long id = requiredId(json, "issue comment");
        Object url = json.get("html_url");
        return new IssueComment(id, url instanceof String s ? s : null);
    }

    private static long requiredId(Map<?, ?> json, String operation) {
        if (json != null && json.get("id") instanceof Number id) {
            return id.longValue();
        }
        throw new GitHubApiException(GitHubApiException.Kind.TRANSIENT, 200, null,
                operation + " returned no id", null);
    }

    // -----------------------------------------------------------------
    // Error classification
    // -----------------------------------------------------------------

    /** Runs one GitHub call and turns every failure into a classified {@link GitHubApiException}. */
    private static <T> T call(String operation, boolean diffRequest, Supplier<T> request) {
        try {
            return request.get();
        } catch (GitHubApiException ex) {
            throw ex;
        } catch (WebClientResponseException ex) {
            throw classify(operation, diffRequest, ex.getStatusCode().value(), ex.getHeaders(),
                    ex.getResponseBodyAsString(), Instant.now(), ex);
        } catch (RuntimeException ex) {
            if (diffRequest && hasCause(ex, DataBufferLimitException.class)) {
                throw new GitHubApiException(GitHubApiException.Kind.DIFF_TOO_LARGE, 0, null,
                        "pull request diff exceeds " + MAX_RESPONSE_BYTES + " bytes", ex);
            }
            // Timeouts and I/O errors. Only the exception type is kept: messages
            // can carry request URLs, which add nothing to an operator-facing error.
            throw new GitHubApiException(GitHubApiException.Kind.TRANSIENT, 0, null,
                    operation + " failed: " + ex.getClass().getSimpleName(), ex);
        }
    }

    /**
     * 401 → revoked token; 404/410 → gone; 403/429 with rate-limit signals →
     * retry at the reset time; other 403 → missing permission; 406/422 on a
     * diff → too large; 422 otherwise → payload rejected; anything else
     * (5xx, unexpected statuses) → transient.
     */
    static GitHubApiException classify(String operation, boolean diffRequest, int status,
                                       HttpHeaders headers, String body, Instant now, Throwable cause) {
        String prefix = operation + ": GitHub returned HTTP " + status;
        if (status == 401) {
            return new GitHubApiException(GitHubApiException.Kind.AUTH_REVOKED, status, null,
                    prefix + " (the repository owner's GitHub token was revoked or expired; reconnect the repository)",
                    cause);
        }
        if (status == 429 || (status == 403 && isRateLimited(headers, body))) {
            Instant retryAt = rateLimitRetryAt(headers, now);
            return new GitHubApiException(GitHubApiException.Kind.RATE_LIMITED, status, retryAt,
                    prefix + " (rate limited; retry after " + retryAt + ")", cause);
        }
        if (status == 403) {
            return new GitHubApiException(GitHubApiException.Kind.FORBIDDEN, status, null,
                    prefix + " (the token lacks permission for this repository)", cause);
        }
        if (status == 404 || status == 410) {
            return new GitHubApiException(GitHubApiException.Kind.NOT_FOUND, status, null,
                    prefix + " (repository, pull request or comment no longer exists or is not visible)", cause);
        }
        if (diffRequest && (status == 406 || status == 422)) {
            return new GitHubApiException(GitHubApiException.Kind.DIFF_TOO_LARGE, status, null,
                    prefix + " (GitHub cannot render a diff this large)", cause);
        }
        if (status == 422) {
            return new GitHubApiException(GitHubApiException.Kind.UNPROCESSABLE, status, null,
                    prefix + " (request rejected as unprocessable)", cause);
        }
        return new GitHubApiException(GitHubApiException.Kind.TRANSIENT, status, null, prefix, cause);
    }

    private static boolean isRateLimited(HttpHeaders headers, String body) {
        if (headers.getFirst(HttpHeaders.RETRY_AFTER) != null
                || "0".equals(headers.getFirst("x-ratelimit-remaining"))) {
            return true;
        }
        return body != null && body.toLowerCase(Locale.ROOT).contains("rate limit");
    }

    /**
     * The later of {@code Retry-After} and {@code x-ratelimit-reset}, never
     * sooner than one second nor later than {@link #MAX_RATE_LIMIT_WAIT};
     * {@link #DEFAULT_RATE_LIMIT_WAIT} when GitHub sent neither header.
     */
    static Instant rateLimitRetryAt(HttpHeaders headers, Instant now) {
        Instant retryAt = null;
        Long retryAfter = parseLong(headers.getFirst(HttpHeaders.RETRY_AFTER));
        if (retryAfter != null && retryAfter >= 0) {
            retryAt = now.plusSeconds(retryAfter);
        }
        Long reset = parseLong(headers.getFirst("x-ratelimit-reset"));
        if (reset != null && reset > 0) {
            Instant resetAt = Instant.ofEpochSecond(reset);
            retryAt = retryAt == null || resetAt.isAfter(retryAt) ? resetAt : retryAt;
        }
        if (retryAt == null) {
            retryAt = now.plus(DEFAULT_RATE_LIMIT_WAIT);
        }
        Instant earliest = now.plusSeconds(1);
        Instant latest = now.plus(MAX_RATE_LIMIT_WAIT);
        return retryAt.isBefore(earliest) ? earliest : retryAt.isAfter(latest) ? latest : retryAt;
    }

    private static Long parseLong(String value) {
        if (value == null) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) return true;
        }
        return false;
    }

    /**
     * {@code owner/name} split into separate URI variables. Passing the full
     * name as one variable percent-encodes the slash ({@code owner%2Fname}),
     * which GitHub answers with 404.
     */
    record RepoPath(String owner, String name) {
        private static final Pattern FULL_NAME = Pattern.compile("^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$");
        static RepoPath of(String fullName) {
            if (fullName == null || !FULL_NAME.matcher(fullName).matches()) {
                throw new IllegalArgumentException("repository full name must match owner/name");
            }
            int slash = fullName.indexOf('/');
            return new RepoPath(fullName.substring(0, slash), fullName.substring(slash + 1));
        }
    }
}
