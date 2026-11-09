package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.dto.response.DiagnosticsResponse;
import com.automatedcodereviewtool.dto.response.DiagnosticsResponse.Component;
import com.automatedcodereviewtool.dto.response.DiagnosticsResponse.MlWorkerComponent;
import com.automatedcodereviewtool.dto.response.DiagnosticsResponse.QueueStats;
import com.automatedcodereviewtool.entity.User;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.netty.channel.ChannelOption;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Health of the API's dependencies and the caller's queue depth, for
 * {@code GET /api/diagnostics}.
 *
 * <p>Every probe runs concurrently on a virtual thread and is bounded by
 * {@code app.diagnostics.probe-timeout-ms} (default 2 s), so the endpoint
 * answers within roughly one timeout even when every dependency hangs. A
 * failed probe becomes a {@code down} component; it never fails the
 * request. Details are fixed strings: exception messages can embed
 * connection URLs or credentials, so they are logged by class name only.</p>
 *
 * <ul>
 *   <li><b>database</b> — latest successful Flyway migration, plus the
 *       queue counts (scoped to the caller's repositories).</li>
 *   <li><b>redis</b> — {@code PING}.</li>
 *   <li><b>mlWorker</b> — {@code GET /ml/health} (unauthenticated on the
 *       worker; the shared secret is not sent). {@code degraded} when the
 *       worker reports a model that failed to load.</li>
 *   <li><b>github</b> — {@code GET /rate_limit}, which GitHub does not count
 *       against the rate limit; cached for one minute.</li>
 * </ul>
 *
 * <p>Overall status: {@code down} when the database is down (nothing
 * works without it), {@code degraded} when any other component is not
 * {@code up}, otherwise {@code up}.</p>
 */
@Service
public class DiagnosticsService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsService.class);

    static final String UP = "up";
    static final String DEGRADED = "degraded";
    static final String DOWN = "down";

    private static final Duration GITHUB_CACHE_TTL = Duration.ofMinutes(1);
    private static final int MAX_DETAIL_CHARS = 120;

    private static final String SCHEMA_VERSION_SQL = """
            SELECT version FROM flyway_schema_history
             WHERE success = TRUE AND version IS NOT NULL
             ORDER BY installed_rank DESC
             LIMIT 1
            """;

    /** Pending = received or processing; retrying = failed with attempts left. */
    private static final String WEBHOOK_QUEUE_SQL = """
            SELECT COALESCE(SUM(CASE WHEN pw.status IN ('received', 'processing') THEN 1 ELSE 0 END), 0) AS pending,
                   COALESCE(SUM(CASE WHEN pw.status = 'failed' THEN 1 ELSE 0 END), 0) AS retrying,
                   COALESCE(SUM(CASE WHEN pw.status = 'dead_letter' THEN 1 ELSE 0 END), 0) AS dead_letter,
                   MIN(CASE WHEN pw.status IN ('received', 'processing', 'failed') THEN pw.processed_at END) AS oldest
              FROM processed_webhooks pw
              JOIN repositories r ON r.id = pw.repo_id
             WHERE r.owner_id = ?
               AND pw.status IN ('received', 'processing', 'failed', 'dead_letter')
            """;

    private static final String OUTBOX_QUEUE_SQL = """
            SELECT COALESCE(SUM(CASE WHEN o.status IN ('pending', 'processing') THEN 1 ELSE 0 END), 0) AS pending,
                   COALESCE(SUM(CASE WHEN o.status IN ('failed', 'dead_letter') THEN 1 ELSE 0 END), 0) AS failed
              FROM ml.ingestion_outbox o
              JOIN pull_requests pr ON pr.id = o.aggregate_id
              JOIN repositories r ON r.id = pr.repo_id
             WHERE o.aggregate_type = 'pull_request'
               AND r.owner_id = ?
               AND o.status IN ('pending', 'processing', 'failed', 'dead_letter')
            """;

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final WebClient mlWorkerClient;
    private final WebClient githubClient;
    private final Duration probeTimeout;
    private final Clock clock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private volatile CachedComponent cachedGithub;

    @Autowired
    public DiagnosticsService(JdbcTemplate jdbc,
                              StringRedisTemplate redis,
                              WebClient.Builder webClientBuilder,
                              @Value("${app.ml-worker.url}") String mlWorkerUrl,
                              @Value("${app.diagnostics.github-api-url:https://api.github.com}") String githubApiUrl,
                              @Value("${app.diagnostics.probe-timeout-ms:2000}") long probeTimeoutMs) {
        this(jdbc, redis, webClientBuilder, mlWorkerUrl, githubApiUrl,
                Duration.ofMillis(probeTimeoutMs), Clock.systemUTC());
    }

    DiagnosticsService(JdbcTemplate jdbc,
                       StringRedisTemplate redis,
                       WebClient.Builder webClientBuilder,
                       String mlWorkerUrl,
                       String githubApiUrl,
                       Duration probeTimeout,
                       Clock clock) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.probeTimeout = probeTimeout;
        this.clock = clock;
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) probeTimeout.toMillis())
                .responseTimeout(probeTimeout);
        this.mlWorkerClient = webClientBuilder.clone()
                .baseUrl(mlWorkerUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        this.githubClient = webClientBuilder.clone()
                .baseUrl(githubApiUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader(HttpHeaders.USER_AGENT, "automated-code-review-tool-diagnostics")
                .build();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** Probe every dependency concurrently; never throws. */
    public DiagnosticsResponse check(User caller) {
        UUID ownerId = caller.getId();
        Future<DatabaseResult> database = executor.submit(() -> probeDatabase(ownerId));
        Future<Component> redisProbe = executor.submit(this::probeRedis);
        Future<MlWorkerComponent> mlWorker = executor.submit(this::probeMlWorker);
        Future<Component> github = executor.submit(this::probeGithub);

        DatabaseResult db = await(database, "database",
                reason -> new DatabaseResult(new Component(DOWN, reason), null));
        Component redisStatus = await(redisProbe, "redis", reason -> new Component(DOWN, reason));
        MlWorkerComponent ml = await(mlWorker, "mlWorker", reason -> new MlWorkerComponent(DOWN, null, reason));
        Component githubStatus = await(github, "github", reason -> new Component(DOWN, reason));

        String overall;
        if (DOWN.equals(db.component().status())) {
            overall = DOWN;
        } else if (Stream.of(redisStatus.status(), ml.status(), githubStatus.status()).allMatch(UP::equals)) {
            overall = UP;
        } else {
            overall = DEGRADED;
        }
        return new DiagnosticsResponse(
                overall,
                clock.instant(),
                new DiagnosticsResponse.Components(db.component(), redisStatus, ml, githubStatus),
                db.queue());
    }

    /**
     * Wait for a probe up to the probe timeout (plus a small margin for
     * scheduling). Probes catch their own failures; this covers hangs and
     * anything unexpected.
     */
    private <T> T await(Future<T> future, String name, Function<String, T> onFailure) {
        try {
            return future.get(probeTimeout.toMillis() + 250, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            log.warn("Diagnostics probe {} timed out", name);
            return onFailure.apply("timed out");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return onFailure.apply("interrupted");
        } catch (ExecutionException ex) {
            log.warn("Diagnostics probe {} failed: {}", name, ex.getCause().getClass().getSimpleName());
            return onFailure.apply("check failed");
        }
    }

    // --- database + queue ----------------------------------------------------

    record DatabaseResult(Component component, QueueStats queue) {
    }

    private DatabaseResult probeDatabase(UUID ownerId) {
        String version;
        try {
            version = jdbc.queryForList(SCHEMA_VERSION_SQL, String.class).stream().findFirst().orElse(null);
        } catch (RuntimeException ex) {
            log.warn("Diagnostics: database check failed ({})", ex.getClass().getSimpleName());
            return new DatabaseResult(new Component(DOWN, "unreachable"), null);
        }
        Component component = new Component(UP,
                version == null ? "no migrations recorded" : "schema version " + version);
        try {
            return new DatabaseResult(component, queueStats(ownerId));
        } catch (RuntimeException ex) {
            log.warn("Diagnostics: queue query failed ({})", ex.getClass().getSimpleName());
            return new DatabaseResult(new Component(DEGRADED, "queue statistics unavailable"), null);
        }
    }

    private QueueStats queueStats(UUID ownerId) {
        Instant now = clock.instant();
        long[] webhooks = new long[3];
        Long[] oldestSeconds = new Long[1];
        jdbc.query(WEBHOOK_QUEUE_SQL, rs -> {
            webhooks[0] = rs.getLong("pending");
            webhooks[1] = rs.getLong("retrying");
            webhooks[2] = rs.getLong("dead_letter");
            Timestamp oldest = rs.getTimestamp("oldest");
            oldestSeconds[0] = oldest == null ? null
                    : Math.max(0, Duration.between(oldest.toInstant(), now).toSeconds());
        }, ownerId);
        long[] outbox = new long[2];
        jdbc.query(OUTBOX_QUEUE_SQL, rs -> {
            outbox[0] = rs.getLong("pending");
            outbox[1] = rs.getLong("failed");
        }, ownerId);
        return new QueueStats(webhooks[0], webhooks[1], webhooks[2], oldestSeconds[0], outbox[0], outbox[1]);
    }

    // --- redis ---------------------------------------------------------------

    private Component probeRedis() {
        try {
            String pong = redis.execute((RedisCallback<String>) connection -> connection.ping());
            return "PONG".equalsIgnoreCase(pong)
                    ? new Component(UP, "PING ok")
                    : new Component(DOWN, "unexpected PING reply");
        } catch (RuntimeException ex) {
            log.warn("Diagnostics: Redis check failed ({})", ex.getClass().getSimpleName());
            return new Component(DOWN, "unreachable");
        }
    }

    // --- ML worker -----------------------------------------------------------

    /** Subset of the worker's {@code HealthResponse} (apps/ml-worker/app/schemas.py). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MlHealth(String status, Boolean modelLoaded, String modelName, String engine, String degradedReason) {
    }

    private MlWorkerComponent probeMlWorker() {
        MlHealth health;
        try {
            health = mlWorkerClient.get()
                    .uri("/ml/health")
                    .retrieve()
                    .bodyToMono(MlHealth.class)
                    .block(probeTimeout);
        } catch (WebClientResponseException ex) {
            return new MlWorkerComponent(DOWN, null, "HTTP " + ex.getStatusCode().value());
        } catch (RuntimeException ex) {
            log.warn("Diagnostics: ML worker check failed ({})", ex.getClass().getSimpleName());
            return new MlWorkerComponent(DOWN, null, isTimeout(ex) ? "timed out" : "unreachable");
        }
        if (health == null || !"healthy".equalsIgnoreCase(health.status())) {
            return new MlWorkerComponent(DOWN, health == null ? null : health.engine(), "worker reported unhealthy");
        }
        String engine = health.engine();
        if (health.degradedReason() != null && !health.degradedReason().isBlank()) {
            return new MlWorkerComponent(DEGRADED, engine,
                    "model unavailable (" + safeDetail(health.degradedReason()) + "); rule-based fallback active");
        }
        String detail = Boolean.TRUE.equals(health.modelLoaded())
                ? "model " + safeDetail(health.modelName()) + " loaded"
                : "rule-based engine (no model configured)";
        return new MlWorkerComponent(UP, engine, detail);
    }

    // --- GitHub --------------------------------------------------------------

    private record CachedComponent(Component component, Instant expiresAt) {
    }

    private Component probeGithub() {
        Instant now = clock.instant();
        CachedComponent cached = cachedGithub;
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.component();
        }
        Component component;
        try {
            githubClient.get()
                    .uri("/rate_limit")
                    .retrieve()
                    .toBodilessEntity()
                    .block(probeTimeout);
            component = new Component(UP, "api reachable");
        } catch (WebClientResponseException ex) {
            int status = ex.getStatusCode().value();
            component = status == 403 || status == 429
                    ? new Component(DEGRADED, "api rate limited (HTTP " + status + ")")
                    : new Component(DOWN, "HTTP " + status);
        } catch (RuntimeException ex) {
            log.warn("Diagnostics: GitHub check failed ({})", ex.getClass().getSimpleName());
            component = new Component(DOWN, isTimeout(ex) ? "timed out" : "unreachable");
        }
        cachedGithub = new CachedComponent(component, now.plus(GITHUB_CACHE_TTL));
        return component;
    }

    // --- helpers -------------------------------------------------------------

    private static boolean isTimeout(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException
                    || t instanceof io.netty.handler.timeout.ReadTimeoutException
                    || t instanceof io.netty.channel.ConnectTimeoutException
                    || (t instanceof IllegalStateException && String.valueOf(t.getMessage()).startsWith("Timeout"))) {
                return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    /** Printable ASCII only, bounded: worker-provided text is echoed to the caller. */
    private static String safeDetail(String value) {
        if (value == null) return "unknown";
        String printable = value.replaceAll("[^\\x20-\\x7E]", "").strip();
        return printable.length() <= MAX_DETAIL_CHARS ? printable : printable.substring(0, MAX_DETAIL_CHARS);
    }

    /** Visible for tests: drop the cached GitHub result. */
    void clearGithubCache() {
        cachedGithub = null;
    }
}
