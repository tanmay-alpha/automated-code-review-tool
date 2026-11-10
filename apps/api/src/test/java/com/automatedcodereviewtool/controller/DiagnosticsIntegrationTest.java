package com.automatedcodereviewtool.controller;

import com.automatedcodereviewtool.entity.PullRequestEntity;
import com.automatedcodereviewtool.entity.Repository;
import com.automatedcodereviewtool.entity.User;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/diagnostics} end to end on H2: real database probe and
 * caller-scoped queue counts; Redis mocked; ML worker and GitHub served by
 * a MockWebServer whose health answer each test chooses.
 */
@TestPropertySource(properties = {
        // Keep the background queue workers from claiming the fixture rows.
        "app.webhook.recovery-delay-ms=3600000",
        "app.outbox.poll-delay-ms=3600000",
        "app.diagnostics.probe-timeout-ms=1000"
})
class DiagnosticsIntegrationTest extends OwnedApiTestSupport {

    static final MockWebServer SERVER = new MockWebServer();
    static volatile MockResponse mlHealth;

    static {
        SERVER.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("/ml/health".equals(request.getPath())) {
                    return mlHealth;
                }
                if ("/rate_limit".equals(request.getPath())) {
                    return new MockResponse().setResponseCode(200).setBody("{}");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        try {
            SERVER.start();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        String base = "http://" + SERVER.getHostName() + ":" + SERVER.getPort();
        registry.add("app.ml-worker.url", () -> base);
        registry.add("app.diagnostics.github-api-url", () -> base);
    }

    @AfterAll
    static void stopServer() throws IOException {
        SERVER.shutdown();
    }

    @MockBean StringRedisTemplate redis;

    private User alice;

    @BeforeEach
    void healthyDependencies() {
        reset(redis);
        when(redis.execute(any(RedisCallback.class))).thenReturn("PONG");
        mlHealth = new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""
                        {"status":"healthy","modelLoaded":false,"modelName":"none","device":"cpu",
                         "engine":"fallback","taxonomyVersion":"1.0.0","degradedReason":null}
                        """);
        alice = newUser("diag-alice");
    }

    @Test
    void allDependenciesUp_reportsUp() throws Exception {
        mockMvc.perform(as(alice, get("/api/diagnostics")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("up"))
                .andExpect(jsonPath("$.checkedAt").isNotEmpty())
                .andExpect(jsonPath("$.components.database.status").value("up"))
                .andExpect(jsonPath("$.components.database.detail").value(
                        org.hamcrest.Matchers.matchesPattern("schema version \\d+")))
                .andExpect(jsonPath("$.components.redis.status").value("up"))
                .andExpect(jsonPath("$.components.mlWorker.status").value("up"))
                .andExpect(jsonPath("$.components.mlWorker.engine").value("fallback"))
                .andExpect(jsonPath("$.components.github.status").value("up"))
                .andExpect(jsonPath("$.queue.webhooksPending").value(0))
                .andExpect(jsonPath("$.queue.oldestPendingSeconds").doesNotExist());
    }

    @Test
    void redisAndMlWorkerDown_reportDegradedWith200() throws Exception {
        when(redis.execute(any(RedisCallback.class)))
                .thenThrow(new RedisConnectionFailureException("redis://:s3cret@cache.internal:6379 refused"));
        mlHealth = new MockResponse().setResponseCode(503);

        String body = mockMvc.perform(as(alice, get("/api/diagnostics")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("degraded"))
                .andExpect(jsonPath("$.components.database.status").value("up"))
                .andExpect(jsonPath("$.components.redis.status").value("down"))
                .andExpect(jsonPath("$.components.redis.detail").value("unreachable"))
                .andExpect(jsonPath("$.components.mlWorker.status").value("down"))
                .andExpect(jsonPath("$.components.mlWorker.detail").value("HTTP 503"))
                .andExpect(jsonPath("$.queue").exists())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("s3cret", "cache.internal", "127.0.0.1", "Exception", "at com.");
    }

    @Test
    void queueCountsAreScopedToTheCallersRepositories() throws Exception {
        User bob = newUser("diag-bob");
        Repository aliceRepo = newRepo(alice, "alice/app");
        Repository bobRepo = newRepo(bob, "bob/app");
        PullRequestEntity alicePr = newPr(aliceRepo, 1, "reviewed");
        PullRequestEntity bobPr = newPr(bobRepo, 1, "reviewed");

        Instant now = Instant.now();
        delivery(aliceRepo, "received", now.minus(Duration.ofMinutes(10)));
        delivery(aliceRepo, "processing", now.minus(Duration.ofMinutes(2)));
        delivery(aliceRepo, "failed", now.minus(Duration.ofMinutes(5)));
        delivery(aliceRepo, "dead_letter", now.minus(Duration.ofHours(3)));
        delivery(aliceRepo, "completed", now.minus(Duration.ofHours(5)));
        delivery(bobRepo, "received", now.minus(Duration.ofHours(9)));
        delivery(bobRepo, "dead_letter", now);
        outbox(alicePr, "pending");
        outbox(alicePr, "dead_letter");
        outbox(alicePr, "completed");
        outbox(bobPr, "pending");
        outbox(bobPr, "failed");

        mockMvc.perform(as(alice, get("/api/diagnostics")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queue.webhooksPending").value(2))
                .andExpect(jsonPath("$.queue.webhooksRetrying").value(1))
                .andExpect(jsonPath("$.queue.webhooksDeadLetter").value(1))
                .andExpect(jsonPath("$.queue.oldestPendingSeconds").value(
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.greaterThanOrEqualTo(595),
                                org.hamcrest.Matchers.lessThan(900))))
                .andExpect(jsonPath("$.queue.outboxPending").value(1))
                .andExpect(jsonPath("$.queue.outboxFailed").value(1));

        mockMvc.perform(as(bob, get("/api/diagnostics")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queue.webhooksPending").value(1))
                .andExpect(jsonPath("$.queue.webhooksDeadLetter").value(1))
                .andExpect(jsonPath("$.queue.outboxPending").value(1))
                .andExpect(jsonPath("$.queue.outboxFailed").value(1));
    }

    @Test
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/diagnostics")).andExpect(status().isUnauthorized());
    }

    private void delivery(Repository repo, String status, Instant receivedAt) {
        // available_at in the future: no worker may claim the fixture rows.
        jdbc.update("""
                        INSERT INTO processed_webhooks (delivery_id, processed_at, repo_id, github_pr_number, head_sha,
                            action, status, attempt_count, max_attempts, available_at, processing_started_at, updated_at)
                        VALUES (?, ?, ?, 1, ?, 'opened', ?, 0, 5, ?, ?, ?)
                        """,
                UUID.randomUUID().toString(), Timestamp.from(receivedAt), repo.getId(),
                UUID.randomUUID().toString().replace("-", ""), status,
                Timestamp.from(Instant.now().plus(Duration.ofDays(1))),
                "processing".equals(status) ? Timestamp.from(Instant.now()) : null,
                Timestamp.from(Instant.now()));
    }

    private void outbox(PullRequestEntity pr, String status) {
        jdbc.update("""
                        INSERT INTO ml.ingestion_outbox (id, event_type, aggregate_type, aggregate_id, status,
                            attempt_count, max_attempts, available_at, locked_at, created_at, updated_at)
                        VALUES (?, 'DATASET_CAPTURE_REQUESTED', 'pull_request', ?, ?, 0, 5, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(), pr.getId(), status,
                Timestamp.from(Instant.now().plus(Duration.ofDays(1))),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }
}
