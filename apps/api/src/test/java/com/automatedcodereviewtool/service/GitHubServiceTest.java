package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.config.GitHubConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class GitHubServiceTest {

    private MockWebServer server;
    private GitHubService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        service = new GitHubService(WebClient.builder(), config(),
                server.url("/").toString().replaceAll("/$", ""));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void getOAuthRedirectUrl_encodesQueryParameters() {
        GitHubService oauthService = new GitHubService(WebClient.builder(), config());

        String url = oauthService.getOAuthRedirectUrl();

        assertThat(URI.create(url)).isNotNull();
        assertThat(url).contains("client_id=test-client");
        assertThat(url).contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fauth%2Fcallback");
        assertThat(url).contains("scope=read%3Auser+repo");
    }

    @Test
    void repositoryFullNameIsSentAsTwoPathSegments() throws Exception {
        server.enqueue(new MockResponse().setBody("diff --git a/x b/x\n"));

        service.getFileDiff("tok", "octo/hello-world", 7);

        RecordedRequest request = server.takeRequest();
        // A single {repo} variable would encode the slash ("octo%2Fhello-world"): GitHub 404s.
        assertThat(request.getPath()).isEqualTo("/repos/octo/hello-world/pulls/7");
        assertThat(request.getHeader("Accept")).isEqualTo("application/vnd.github.v3.diff");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer tok");
    }

    @Test
    void malformedRepositoryNameIsRejectedBeforeAnyCall() {
        assertThatThrownBy(() -> service.getFileDiff("tok", "no-slash", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getFileDiff("tok", "a/b/c", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void allRepositoryEndpointsUseSeparateValidatedSegments() throws Exception {
        server.enqueue(json(200, "{\"id\":1}"));
        assertThat(service.installWebhook("tok", "owner/name", "https://example.test/hook", "secret")).isEqualTo(1L);
        assertThat(server.takeRequest().getPath()).isEqualTo("/repos/owner/name/hooks");
        server.enqueue(new MockResponse().setResponseCode(204));
        service.deleteWebhook("tok", "owner/name", 1L);
        assertThat(server.takeRequest().getPath()).isEqualTo("/repos/owner/name/hooks/1");
        server.enqueue(json(200, "{\"id\":1}"));
        service.getRepository("tok", "owner", "name");
        assertThat(server.takeRequest().getPath()).isEqualTo("/repos/owner/name");
        server.enqueue(json(200, "{\"head\":{\"sha\":\"abc\"}}"));
        service.getPullRequestHeadSha("tok", "owner/name", 7);
        assertThat(server.takeRequest().getPath()).isEqualTo("/repos/owner/name/pulls/7");
        server.enqueue(json(200, "{\"id\":1}"));
        service.createIssueComment("tok", "owner/name", 7, "body");
        assertThat(server.takeRequest().getPath()).isEqualTo("/repos/owner/name/issues/7/comments");
        server.enqueue(json(200, "[]"));
        service.findReview("tok", "owner/name", 7, "<!-- marker -->", 42);
        assertThat(server.takeRequest().getPath())
                .isEqualTo("/repos/owner/name/pulls/7/reviews?per_page=50&page=1");
    }

    @Test
    void repositoryNamesRejectUntrustedPathCharacters() {
        for (String invalid : new String[]{"a_/b", "a/b?x", "a/b#x", "a/b%2Fc", "a/b c", "a/β", "a\\b/c"}) {
            assertThatThrownBy(() -> service.installWebhook("tok", invalid, "https://example.test", "secret"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.getRepository("tok", "owner/other", "name"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(server.getRequestCount()).isZero();
        assertThat(GitHubService.RepoPath.of("owner-123/repo.name_-")).isNotNull();
    }

    @Test
    void incompleteMarkerLookupFailsRatherThanAllowingADuplicateCreate() {
        for (int page = 0; page < GitHubService.MAX_PAGES; page++) {
            server.enqueue(json(200, "[]").setHeader("Link", "<https://api.github.com/x?page=2>; rel=\"next\""));
        }
        GitHubApiException ex = catchThrowableOfType(
                () -> service.findIssueComment("tok", "owner/name", 7, "<!-- marker -->", 42),
                GitHubApiException.class);
        assertThat(ex.retryable()).isTrue();
        assertThat(ex.getMessage()).contains("no external write attempted");
    }

    @Test
    void status401IsATerminalRevokedToken() {
        server.enqueue(json(401, "{\"message\":\"Bad credentials\"}"));

        GitHubApiException ex = catchThrowableOfType(
                () -> service.getFileDiff("tok", "o/r", 1), GitHubApiException.class);

        assertThat(ex.kind()).isEqualTo(GitHubApiException.Kind.AUTH_REVOKED);
        assertThat(ex.code()).isEqualTo("github_auth_revoked");
        assertThat(ex.retryable()).isFalse();
        assertThat(ex.describe()).startsWith("github_auth_revoked: fetch pull request diff: GitHub returned HTTP 401");
    }

    @Test
    void status404IsTerminalNotFound() {
        server.enqueue(json(404, "{\"message\":\"Not Found\"}"));

        GitHubApiException ex = catchThrowableOfType(
                () -> service.getPullRequestHeadSha("tok", "o/r", 1), GitHubApiException.class);

        assertThat(ex.kind()).isEqualTo(GitHubApiException.Kind.NOT_FOUND);
        assertThat(ex.retryable()).isFalse();
    }

    @Test
    void diffTooLargeIsTerminalFor406And422() {
        server.enqueue(json(406, "{\"message\":\"Sorry, the diff exceeded the maximum number of files\"}"));
        server.enqueue(json(422, "{\"message\":\"diff too large\"}"));

        for (int i = 0; i < 2; i++) {
            GitHubApiException ex = catchThrowableOfType(
                    () -> service.getFileDiff("tok", "o/r", 1), GitHubApiException.class);
            assertThat(ex.kind()).isEqualTo(GitHubApiException.Kind.DIFF_TOO_LARGE);
            assertThat(ex.code()).isEqualTo("diff_too_large");
        }
    }

    @Test
    void status422OnAWriteIsUnprocessableNotDiffTooLarge() {
        server.enqueue(json(422, "{\"message\":\"Validation Failed\"}"));

        GitHubApiException ex = catchThrowableOfType(
                () -> service.createIssueComment("tok", "o/r", 1, "x"), GitHubApiException.class);

        assertThat(ex.kind()).isEqualTo(GitHubApiException.Kind.UNPROCESSABLE);
    }

    @Test
    void primaryRateLimitRetriesAtTheReset() {
        long reset = Instant.now().plusSeconds(900).getEpochSecond();
        server.enqueue(json(403, "{\"message\":\"API rate limit exceeded\"}")
                .setHeader("x-ratelimit-remaining", "0")
                .setHeader("x-ratelimit-reset", Long.toString(reset)));

        GitHubApiException ex = catchThrowableOfType(
                () -> service.getFileDiff("tok", "o/r", 1), GitHubApiException.class);

        assertThat(ex.kind()).isEqualTo(GitHubApiException.Kind.RATE_LIMITED);
        assertThat(ex.retryable()).isTrue();
        assertThat(ex.retryAt()).isEqualTo(Instant.ofEpochSecond(reset));
    }

    @Test
    void secondaryRateLimitHonoursRetryAfter() {
        server.enqueue(json(403, "{\"message\":\"You have exceeded a secondary rate limit\"}")
                .setHeader("Retry-After", "90"));
        Instant before = Instant.now();

        GitHubApiException ex = catchThrowableOfType(
                () -> service.createIssueComment("tok", "o/r", 1, "x"), GitHubApiException.class);

        assertThat(ex.kind()).isEqualTo(GitHubApiException.Kind.RATE_LIMITED);
        assertThat(ex.retryAt()).isBetween(before.plusSeconds(89), Instant.now().plusSeconds(91));
    }

    @Test
    void status429WithoutHeadersWaitsTheDocumentedMinute() {
        server.enqueue(json(429, "{}"));
        Instant before = Instant.now();

        GitHubApiException ex = catchThrowableOfType(
                () -> service.getFileDiff("tok", "o/r", 1), GitHubApiException.class);

        assertThat(ex.kind()).isEqualTo(GitHubApiException.Kind.RATE_LIMITED);
        assertThat(ex.retryAt()).isBetween(before.plusSeconds(59), Instant.now().plusSeconds(61));
    }

    @Test
    void rateLimitWaitUsesTheLaterSignalAndIsCapped() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        HttpHeaders both = new HttpHeaders();
        both.set("Retry-After", "30");
        both.set("x-ratelimit-reset", Long.toString(now.plusSeconds(300).getEpochSecond()));
        assertThat(GitHubService.rateLimitRetryAt(both, now)).isEqualTo(now.plusSeconds(300));

        HttpHeaders far = new HttpHeaders();
        far.set("x-ratelimit-reset", Long.toString(now.plusSeconds(86_400).getEpochSecond()));
        assertThat(GitHubService.rateLimitRetryAt(far, now)).isEqualTo(now.plus(GitHubService.MAX_RATE_LIMIT_WAIT));

        HttpHeaders past = new HttpHeaders();
        past.set("x-ratelimit-reset", Long.toString(now.minusSeconds(10).getEpochSecond()));
        assertThat(GitHubService.rateLimitRetryAt(past, now)).isEqualTo(now.plusSeconds(1));
    }

    @Test
    void forbiddenWithoutRateLimitSignalsIsTerminal() {
        server.enqueue(json(403, "{\"message\":\"Resource not accessible by integration\"}"));

        GitHubApiException ex = catchThrowableOfType(
                () -> service.createIssueComment("tok", "o/r", 1, "x"), GitHubApiException.class);

        assertThat(ex.kind()).isEqualTo(GitHubApiException.Kind.FORBIDDEN);
        assertThat(ex.retryable()).isFalse();
    }

    @Test
    void serverErrorsAndDroppedConnectionsAreTransient() {
        server.enqueue(json(502, "{}"));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));

        GitHubApiException http = catchThrowableOfType(
                () -> service.getFileDiff("tok", "o/r", 1), GitHubApiException.class);
        GitHubApiException io = catchThrowableOfType(
                () -> service.createIssueComment("tok", "o/r", 1, "x"), GitHubApiException.class);

        assertThat(http.kind()).isEqualTo(GitHubApiException.Kind.TRANSIENT);
        assertThat(http.status()).isEqualTo(502);
        assertThat(io.kind()).isEqualTo(GitHubApiException.Kind.TRANSIENT);
        assertThat(io.status()).isZero();
        assertThat(io.getMessage()).doesNotContain("http://");
    }

    @Test
    void markerLookupFollowsLinkPaginationAndFiltersByAuthor() throws Exception {
        String marker = "<!-- acrt:summary repo=1 pr=7 -->";
        server.enqueue(json(200, "[{\"id\":1,\"body\":\"" + marker.replace("\"", "\\\"")
                + " planted\",\"user\":{\"id\":666}},{\"id\":2,\"body\":\"hi\",\"user\":{\"id\":42}}]")
                .setHeader("Link", "<https://api.github.com/x?page=2>; rel=\"next\""));
        server.enqueue(json(200, "[{\"id\":3,\"body\":\"" + marker + " ours\",\"user\":{\"id\":42},"
                + "\"html_url\":\"https://github.com/o/r/pull/7#issuecomment-3\"}]"));

        GitHubService.IssueComment found = service.findIssueComment("tok", "o/r", 7, marker, 42L).orElseThrow();

        assertThat(found).isEqualTo(new GitHubService.IssueComment(3, "https://github.com/o/r/pull/7#issuecomment-3"));
        assertThat(server.takeRequest().getPath()).isEqualTo("/repos/o/r/issues/7/comments?per_page=50&page=1");
        // Page 2 is requested against the configured API base, not the Link URL.
        assertThat(server.takeRequest().getPath()).isEqualTo("/repos/o/r/issues/7/comments?per_page=50&page=2");
    }

    @Test
    void markerLookupStopsWhenThereIsNoNextPage() {
        server.enqueue(json(200, "[{\"id\":2,\"body\":\"hi\",\"user\":{\"id\":42}}]"));

        assertThat(service.findIssueComment("tok", "o/r", 7, "<!-- m -->", 42L)).isEmpty();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void reviewIsCreatedOnTheReviewedCommitWithRightSideComments() throws Exception {
        server.enqueue(json(200, "{\"id\":77}"));

        long id = service.createReview("tok", "o/r", 7, "abc123", "body",
                List.of(new GitHubService.InlineComment("src/a.py", 2, "c")));

        assertThat(id).isEqualTo(77L);
        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/repos/o/r/pulls/7/reviews");
        assertThat(request.getBody().readUtf8())
                .contains("\"commit_id\":\"abc123\"", "\"event\":\"COMMENT\"",
                        "\"path\":\"src/a.py\"", "\"line\":2", "\"side\":\"RIGHT\"");
    }

    @Test
    void commentIsEditedInPlace() throws Exception {
        server.enqueue(json(200, "{\"id\":9,\"html_url\":\"u\"}"));

        assertThat(service.updateIssueComment("tok", "o/r", 9L, "new"))
                .isEqualTo(new GitHubService.IssueComment(9, "u"));
        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("PATCH");
        assertThat(request.getPath()).isEqualTo("/repos/o/r/issues/comments/9");
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private static GitHubConfig config() {
        GitHubConfig config = new GitHubConfig();
        config.setClientId("test-client");
        config.setClientSecret("test-secret");
        config.setOauthRedirectUri("http://localhost:8080/api/auth/callback");
        return config;
    }
}
