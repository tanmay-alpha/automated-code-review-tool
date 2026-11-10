package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.dto.response.DiagnosticsResponse;
import com.automatedcodereviewtool.entity.User;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DiagnosticsService} probe classification without a Spring context:
 * mocked JDBC and Redis, MockWebServer for the ML worker and GitHub.
 */
class DiagnosticsServiceTest {

    private static final Duration TIMEOUT = Duration.ofMillis(400);
    private static final String HEALTHY_FALLBACK = """
            {"status":"healthy","modelLoaded":false,"modelName":"none","device":"cpu",
             "engine":"fallback","taxonomyVersion":"1.0.0","degradedReason":null}
            """;

    private MockWebServer ml;
    private MockWebServer github;
    private JdbcTemplate jdbc;
    private StringRedisTemplate redis;
    private DiagnosticsService service;
    private final User caller = User.builder().id(UUID.randomUUID()).githubUsername("caller").build();

    @BeforeEach
    void setUp() throws IOException {
        ml = new MockWebServer();
        ml.start();
        github = new MockWebServer();
        github.start();
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of("21"));
        redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisCallback.class))).thenReturn("PONG");
        service = new DiagnosticsService(jdbc, redis, WebClient.builder(),
                "http://" + ml.getHostName() + ":" + ml.getPort(),
                "http://" + github.getHostName() + ":" + github.getPort(),
                TIMEOUT, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() throws IOException {
        service.shutdown();
        ml.shutdown();
        github.shutdown();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    void healthyDependencies_reportUp_withoutSendingTheWorkerSecret() throws Exception {
        ml.enqueue(json(HEALTHY_FALLBACK));
        github.enqueue(json("{}"));

        DiagnosticsResponse response = service.check(caller);

        assertThat(response.status()).isEqualTo("up");
        assertThat(response.components().database()).isEqualTo(
                new DiagnosticsResponse.Component("up", "schema version 21"));
        assertThat(response.components().mlWorker().engine()).isEqualTo("fallback");
        assertThat(response.components().mlWorker().detail()).isEqualTo("rule-based engine (no model configured)");
        assertThat(response.components().github().status()).isEqualTo("up");
        assertThat(response.queue()).isEqualTo(new DiagnosticsResponse.QueueStats(0, 0, 0, null, 0, 0));

        RecordedRequest health = ml.takeRequest(1, TimeUnit.SECONDS);
        assertThat(health.getPath()).isEqualTo("/ml/health");
        assertThat(health.getHeader("X-ML-Worker-Secret")).isNull();
        assertThat(github.takeRequest(1, TimeUnit.SECONDS).getPath()).isEqualTo("/rate_limit");
    }

    @Test
    void databaseDown_reportsDownWithoutQueue_andNeverThrows() {
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenThrow(new DataAccessResourceFailureException("jdbc:postgresql://db:5432/x?password=hunter2"));
        ml.enqueue(json(HEALTHY_FALLBACK));
        github.enqueue(json("{}"));

        DiagnosticsResponse response = service.check(caller);

        assertThat(response.status()).isEqualTo("down");
        assertThat(response.components().database()).isEqualTo(new DiagnosticsResponse.Component("down", "unreachable"));
        assertThat(response.queue()).isNull();
        assertThat(response.toString()).doesNotContain("hunter2", "postgresql");
    }

    @Test
    void redisFailure_isDegraded() {
        when(redis.execute(any(RedisCallback.class))).thenThrow(new IllegalStateException("boom"));
        ml.enqueue(json(HEALTHY_FALLBACK));
        github.enqueue(json("{}"));

        DiagnosticsResponse response = service.check(caller);

        assertThat(response.status()).isEqualTo("degraded");
        assertThat(response.components().redis()).isEqualTo(new DiagnosticsResponse.Component("down", "unreachable"));
    }

    @Test
    void hangingMlWorker_timesOutWithinTheProbeBudget() {
        ml.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        github.enqueue(json("{}"));

        long started = System.nanoTime();
        DiagnosticsResponse response = service.check(caller);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(response.status()).isEqualTo("degraded");
        assertThat(response.components().mlWorker().status()).isEqualTo("down");
        assertThat(response.components().mlWorker().detail()).isEqualTo("timed out");
        assertThat(response.components().mlWorker().engine()).isNull();
        assertThat(elapsedMs).isLessThan(TIMEOUT.toMillis() * 4);
    }

    @Test
    void unreachableMlWorker_isDown() throws IOException {
        ml.shutdown();
        github.enqueue(json("{}"));

        DiagnosticsResponse response = service.check(caller);

        assertThat(response.components().mlWorker().status()).isEqualTo("down");
        assertThat(response.components().mlWorker().detail()).isIn("unreachable", "timed out");
    }

    @Test
    void modelLoadFailure_isDegraded_withSanitizedReason() {
        ml.enqueue(json("""
                {"status":"healthy","modelLoaded":false,"modelName":"none","engine":"fallback",
                 "degradedReason":"OSError\\nmissing checkpoint"}
                """));
        github.enqueue(json("{}"));

        DiagnosticsResponse response = service.check(caller);

        assertThat(response.status()).isEqualTo("degraded");
        assertThat(response.components().mlWorker().status()).isEqualTo("degraded");
        assertThat(response.components().mlWorker().detail())
                .isEqualTo("model unavailable (OSErrormissing checkpoint); rule-based fallback active");
    }

    @Test
    void githubRateLimited_isDegraded_andCachedForAMinute() {
        ml.enqueue(json(HEALTHY_FALLBACK));
        ml.enqueue(json(HEALTHY_FALLBACK));
        github.enqueue(new MockResponse().setResponseCode(403));

        assertThat(service.check(caller).components().github())
                .isEqualTo(new DiagnosticsResponse.Component("degraded", "api rate limited (HTTP 403)"));
        assertThat(service.check(caller).components().github().status()).isEqualTo("degraded");
        assertThat(github.getRequestCount()).isEqualTo(1);
    }

    @Test
    void githubServerError_isDown() {
        ml.enqueue(json(HEALTHY_FALLBACK));
        github.enqueue(new MockResponse().setResponseCode(502));

        DiagnosticsResponse response = service.check(caller);

        assertThat(response.components().github()).isEqualTo(new DiagnosticsResponse.Component("down", "HTTP 502"));
        assertThat(response.status()).isEqualTo("degraded");
    }
}
