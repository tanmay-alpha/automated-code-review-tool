package com.codelens.service;

import com.codelens.config.GitHubConfig;
import com.codelens.dto.GitHubTokenResponse;
import com.codelens.dto.GitHubUserInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

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

    private static final HttpClient HTTP_CLIENT = HttpClient.create()
            .responseTimeout(Duration.ofSeconds(30))
            .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000);

    public GitHubService(WebClient.Builder builder, GitHubConfig config) {
        WebClient.Builder timeoutBuilder = builder.clientConnector(new ReactorClientHttpConnector(HTTP_CLIENT));
        this.oauthClient = timeoutBuilder.baseUrl("https://github.com").build();
        this.apiClient = timeoutBuilder.baseUrl(API_BASE).build();
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
                .block();
    }

    public GitHubUserInfo getCurrentUser(String accessToken) {
        return apiClient.get()
                .uri("/user")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(GitHubUserInfo.class)
                .block();
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
        try {
            return apiClient.get()
                    .uri("/repos/{owner}/{name}", owner, name)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (Exception ex) {
            // 404 / 401 / 403 — all manifest as a thrown exception from WebClient
            log.debug("getRepository failed for {}/{}: {}", owner, name, ex.getMessage());
            return null;
        }
    }

    /**
     * Fetch the unified diff for a PR. The {@code .diff} media type asks
     * GitHub to return raw diff text rather than the JSON metadata.
     */
    public String getFileDiff(String accessToken, String repoFullName, int prNumber) {
        return apiClient.get()
                .uri("/repos/{repo}/pulls/{n}", repoFullName, prNumber)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header(HttpHeaders.ACCEPT, "application/vnd.github.v3.diff")
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    /**
     * Install a repo-level webhook that POSTs to {@code webhookUrl} on
     * pull-request events. Returns the GitHub-assigned webhook id.
     */
    public Long installWebhook(String accessToken, String repoFullName,
                               String webhookUrl, String secret) {
        Map<String, Object> body = Map.of(
                "name", "web",
                "active", true,
                "events", List.of("pull_request"),
                "config", Map.of(
                        "url", webhookUrl,
                        "content_type", "json",
                        "secret", secret));
        Map<?, ?> response = apiClient.post()
                .uri("/repos/{repo}/hooks", repoFullName)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block();
        Object id = response == null ? null : response.get("id");
        return id instanceof Number n ? n.longValue() : null;
    }

    public void deleteWebhook(String accessToken, String repoFullName, Long webhookId) {
        if (webhookId == null) return;
        apiClient.delete()
                .uri("/repos/{repo}/hooks/{id}", repoFullName, webhookId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    public void postPrComment(String accessToken, String repoFullName,
                              int prNumber, String body) {
        apiClient.post()
                .uri("/repos/{repo}/issues/{n}/comments", repoFullName, prNumber)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("body", body))
                .retrieve()
                .toBodilessEntity()
                .block();
    }
}
