package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.dto.MlFinding;
import com.automatedcodereviewtool.dto.MlReviewRequest;
import com.automatedcodereviewtool.dto.MlReviewResponse;
import com.automatedcodereviewtool.exception.InvalidDiffException;
import com.automatedcodereviewtool.exception.MlWorkerException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Client for the internal ML worker (FastAPI, {@code POST /ml/review}).
 *
 * <p>Every request carries the shared {@code X-ML-Worker-Secret} header.</p>
 *
 * <p>Error mapping:</p>
 * <ul>
 *   <li>4xx → {@link InvalidDiffException} (worker rejected the input; not retried)</li>
 *   <li>502/503/504, connect failures → {@link MlWorkerException}, retried up to 3 attempts</li>
 *   <li>other 5xx, timeout, open circuit breaker → {@link MlWorkerException}</li>
 * </ul>
 *
 * <p>Calls block: the PR flow runs on the async webhook executor, and the
 * file-scan endpoint is a synchronous request/response.</p>
 */
@Service
public class MlWorkerService {

    private static final Logger log = LoggerFactory.getLogger(MlWorkerService.class);

    /** Netty response timeout per HTTP attempt. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /** Reactor-level upper bound per attempt (connect + response). */
    private static final Duration OVERALL_DEADLINE = Duration.ofSeconds(45);
    /** TCP connect timeout. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final WebClient client;
    private final CircuitBreaker circuitBreaker;

    @Autowired
    public MlWorkerService(WebClient.Builder builder,
                           @Value("${app.ml-worker.url}") String mlWorkerUrl,
                           @Value("${app.ml-worker.secret}") String mlWorkerSecret,
                           @Qualifier("mlWorkerCircuitBreaker") CircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
        // Build a per-instance WebClient with connection + read timeouts
        // applied at the Reactor Netty HttpClient layer.
        reactor.netty.http.client.HttpClient httpClient =
                reactor.netty.http.client.HttpClient.create()
                        .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS,
                                (int) CONNECT_TIMEOUT.toMillis())
                        .responseTimeout(REQUEST_TIMEOUT);
        this.client = builder
                .baseUrl(mlWorkerUrl)
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .defaultHeader("X-ML-Worker-Secret", mlWorkerSecret)
                .build();
    }

    /** Test-friendly constructor that still exercises a real circuit breaker. */
    public MlWorkerService(WebClient.Builder builder, String mlWorkerUrl, String mlWorkerSecret) {
        this(builder, mlWorkerUrl, mlWorkerSecret,
                CircuitBreaker.ofDefaults("mlWorker-test-" + UUID.randomUUID()));
    }

    /** Review a unified diff (PR flow). */
    public MlReviewResponse review(String diff, String language) {
        return executeWithResilience(() -> post(MlReviewRequest.diff(diff, language), "diff"));
    }

    /** Review a single file's contents (VS Code extension / GitHub Action flow). */
    public MlReviewResponse reviewFile(String content, String language, String filePath) {
        return executeWithResilience(() -> post(MlReviewRequest.file(content, language, filePath), "file"));
    }

    private MlReviewResponse post(MlReviewRequest body, String inputKind) {
        return client.post()
                .uri("/ml/review")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(body)
                .retrieve()
                // The worker's error body is never surfaced: FastAPI's 422 echoes the
                // request ("input": <whole diff>), which would leak source code into
                // logs, pull_requests.error_message, and API responses.
                .onStatus(org.springframework.http.HttpStatusCode::is4xxClientError, resp ->
                        resp.releaseBody().then(Mono.error(rejected(inputKind, resp.statusCode().value()))))
                .onStatus(org.springframework.http.HttpStatusCode::is5xxServerError, resp ->
                        Mono.error(upstreamFailure(resp.statusCode().value())))
                .bodyToMono(MlReviewResponse.class)
                .timeout(OVERALL_DEADLINE)
                .onErrorMap(java.util.concurrent.TimeoutException.class,
                        ex -> new MlWorkerException("ML worker timed out", ex))
                .onErrorMap(WebClientResponseException.class,
                        ex -> ex.getStatusCode().is4xxClientError()
                                ? rejected(inputKind, ex.getStatusCode().value())
                                : upstreamFailure(ex.getStatusCode().value()))
                .onErrorMap(java.net.ConnectException.class,
                        ex -> new MlWorkerException("ML worker unavailable", ex))
                .onErrorMap(java.nio.channels.UnresolvedAddressException.class,
                        ex -> new MlWorkerException("ML worker unavailable", ex))
                .onErrorMap(java.io.IOException.class,
                        ex -> new MlWorkerException("ML worker unavailable", ex))
                .onErrorMap(WebClientRequestException.class,
                        ex -> new MlWorkerException("ML worker unavailable", ex, true))
                .doOnError(ex -> log.warn("ML worker {} review failed: {} ({})",
                        inputKind, ex.getMessage(), ex.getClass().getSimpleName()))
                .block();
    }

    private <T> T executeWithResilience(Supplier<T> operation) {
        final int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return circuitBreaker.executeSupplier(operation);
            } catch (InvalidDiffException ex) {
                throw ex;
            } catch (CallNotPermittedException ex) {
                throw new MlWorkerException("ML worker circuit breaker is open", ex, false);
            } catch (MlWorkerException ex) {
                if (!ex.isRetryable() || attempt == maxAttempts) {
                    throw ex;
                }
                sleepBeforeRetry(attempt);
            }
        }
        throw new IllegalStateException("unreachable retry state");
    }

    private static MlWorkerException upstreamFailure(int status) {
        boolean retryable = status == 502 || status == 503 || status == 504;
        return new MlWorkerException("ML worker unavailable: " + status, status, retryable);
    }

    /** Status code and a fixed reason only — see {@link #post}. */
    private static InvalidDiffException rejected(String inputKind, int status) {
        return new InvalidDiffException(status, "ML worker rejected " + inputKind + ": HTTP " + status);
    }

    private static void sleepBeforeRetry(int attempt) {
        long baseMillis = 100L << Math.min(attempt - 1, 4);
        long jitterMillis = ThreadLocalRandom.current().nextLong(50L, 151L);
        try {
            Thread.sleep(baseMillis + jitterMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MlWorkerException("ML worker retry interrupted", ex, false);
        }
    }

    /**
     * Quality score for a worker response: the worker's {@code qualityScore}
     * clamped to [0, 100], or — if it was omitted — the same formula the
     * worker uses ({@code compute_quality_score} in apps/ml-worker/app/model.py): 100 minus the sum of
     * severity weight (critical=20, major=10, minor=3) × confidence,
     * clamped to [0, 100] and rounded half-up to 2 dp. Both sides are
     * checked against contracts/quality_score_cases.json.
     */
    public static BigDecimal computeQualityScore(MlReviewResponse response) {
        if (response == null) return BigDecimal.ZERO;
        BigDecimal ws = response.qualityScore();
        if (ws != null) {
            return ws.min(BigDecimal.valueOf(100)).max(BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP);
        }
        if (response.findings() == null || response.findings().isEmpty()) {
            return BigDecimal.valueOf(100);
        }
        BigDecimal penalty = BigDecimal.ZERO;
        for (MlFinding f : response.findings()) {
            BigDecimal conf = f.confidence() == null ? BigDecimal.ZERO : f.confidence();
            // Clamp to [0,1] so an invalid confidence cannot distort the score.
            BigDecimal clamped = conf.max(BigDecimal.ZERO).min(BigDecimal.ONE);
            BigDecimal weight = switch (f.severity() == null ? "minor" : f.severity().toLowerCase(Locale.ROOT)) {
                case "critical" -> BigDecimal.valueOf(20);
                case "major" -> BigDecimal.valueOf(10);
                default -> BigDecimal.valueOf(3);
            };
            penalty = penalty.add(weight.multiply(clamped));
        }
        BigDecimal score = BigDecimal.valueOf(100).subtract(penalty);
        return score.max(BigDecimal.ZERO).min(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Detect the dominant language in a unified diff by counting
     * added/removed lines per file-extension bucket. Falls back to
     * {@code "python"} if no recognisable extension is present.
     */
    public static String detectLanguage(String diff) {
        if (diff == null || diff.isBlank()) return "python";
        int py = 0, js = 0, java = 0;
        String currentFile = null;
        for (String raw : diff.split("\n")) {
            String line = raw.stripLeading();
            if (line.startsWith("diff --git")) {
                int b = line.indexOf(" b/");
                if (b > 0) {
                    currentFile = line.substring(b + 3);
                } else {
                    currentFile = null;
                }
            }
            if (currentFile != null && (line.startsWith("+") || line.startsWith("-"))
                    && !line.startsWith("+++") && !line.startsWith("---")) {
                String lower = currentFile.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".py")) py++;
                else if (lower.endsWith(".js") || lower.endsWith(".jsx")
                        || lower.endsWith(".ts") || lower.endsWith(".tsx")) js++;
                else if (lower.endsWith(".java")) java++;
            }
        }
        if (py >= js && py >= java && py > 0) return "python";
        if (js >= py && js >= java && js > 0) return "javascript";
        if (java > 0) return "java";
        return "python";
    }
}
