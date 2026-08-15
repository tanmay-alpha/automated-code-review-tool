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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Thin client for the internal ML worker (FastAPI, port 8000).
 *
 * <p>The worker is internal-only; we authenticate every request with
 * the {@code X-ML-Worker-Secret} header. We never call it from outside
 * the API's service mesh.</p>
 *
 * <p>Error mapping:</p>
 * <ul>
 *   <li>4xx → {@link InvalidDiffException} (worker rejected the diff)</li>
 *   <li>5xx → {@link MlWorkerException} ("ML worker unavailable")</li>
 *   <li>timeout / connect-fail → {@link MlWorkerException}
 *       ("ML worker timed out" / "ML worker unavailable")</li>
 * </ul>
 *
 * <p>{@code block()} is intentional: callers (e.g. {@code WebhookService})
 * are already on a {@code @Async} thread, so the blocking call doesn't
 * tie up a Tomcat request thread.</p>
 */
@Service
public class MlWorkerService {

    private static final Logger log = LoggerFactory.getLogger(MlWorkerService.class);

    /** Worker-side timeout for the model inference itself. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /** Total time we'll wait across one connect + one retry before giving up. */
    private static final Duration OVERALL_DEADLINE = Duration.ofSeconds(45);
    /** Connection-level timeout. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final WebClient client;
    private final String mlWorkerSecret;
    private final String mlWorkerUrl;
    private final CircuitBreaker circuitBreaker;

    @Autowired
    public MlWorkerService(WebClient.Builder builder,
                           @Value("${app.ml-worker.url}") String mlWorkerUrl,
                           @Value("${app.ml-worker.secret}") String mlWorkerSecret,
                           @Qualifier("mlWorkerCircuitBreaker") CircuitBreaker circuitBreaker) {
        this.mlWorkerUrl = mlWorkerUrl;
        this.mlWorkerSecret = mlWorkerSecret;
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

    /**
     * Call {@code POST /ml/review} and return the parsed response.
     *
     * <p>Error mapping:</p>
     * <ul>
     *   <li>4xx → {@link InvalidDiffException}</li>
     *   <li>5xx → {@link MlWorkerException}</li>
     *   <li>overall timeout → {@link MlWorkerException} ("timed out")</li>
     *   <li>connection refused / DNS / IO → {@link MlWorkerException} ("unavailable")</li>
     * </ul>
     */
    public MlReviewResponse review(String diff, String language) {
        return executeWithResilience(() -> reviewOnce(diff, language));
    }

    private MlReviewResponse reviewOnce(String diff, String language) {
        MlReviewRequest body = MlReviewRequest.diff(diff, language);
        return client.post()
                .uri("/ml/review")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(body)
                .retrieve()
                .onStatus(org.springframework.http.HttpStatusCode::is4xxClientError, resp ->
                        resp.bodyToMono(String.class).defaultIfEmpty("")
                                .flatMap(body2 -> Mono.error(new InvalidDiffException(
                                        resp.statusCode().value(),
                                        "ML worker rejected diff: " + (body2.isBlank() ? resp.statusCode().toString() : body2)))))
                .onStatus(org.springframework.http.HttpStatusCode::is5xxServerError, resp ->
                        Mono.error(upstreamFailure(resp.statusCode().value())))
                .bodyToMono(MlReviewResponse.class)
                .timeout(OVERALL_DEADLINE)
                .onErrorMap(java.util.concurrent.TimeoutException.class,
                        ex -> new MlWorkerException("ML worker timed out", ex))
                .onErrorMap(WebClientResponseException.class,
                        ex -> ex.getStatusCode().is4xxClientError()
                                ? new InvalidDiffException(ex.getStatusCode().value(),
                                        "ML worker rejected diff: " + ex.getStatusCode())
                                : upstreamFailure(ex.getStatusCode().value()))
                .onErrorMap(java.net.ConnectException.class,
                        ex -> new MlWorkerException("ML worker unavailable", ex))
                .onErrorMap(java.nio.channels.UnresolvedAddressException.class,
                        ex -> new MlWorkerException("ML worker unavailable", ex))
                .onErrorMap(java.io.IOException.class,
                        ex -> new MlWorkerException("ML worker unavailable", ex))
                .onErrorMap(WebClientRequestException.class,
                        ex -> new MlWorkerException("ML worker unavailable", ex, true))
                .doOnError(ex -> log.warn("ML worker call failed: {} ({})",
                        ex.getMessage(), ex.getClass().getSimpleName()))
                .block();
    }

    /**
     * Convenience for the file-scan path.
     */
    public MlReviewResponse reviewFile(String content, String language) {
        return reviewFile(content, language, null);
    }

    public MlReviewResponse reviewFile(String content, String language, String filePath) {
        return executeWithResilience(() -> reviewFileOnce(content, language, filePath));
    }

    private MlReviewResponse reviewFileOnce(String content, String language, String filePath) {
        MlReviewRequest body = MlReviewRequest.file(content, language, filePath);
        return client.post()
                .uri("/ml/review")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(body)
                .retrieve()
                .onStatus(org.springframework.http.HttpStatusCode::is4xxClientError, resp ->
                        resp.bodyToMono(String.class).defaultIfEmpty("")
                                .flatMap(body2 -> Mono.error(new InvalidDiffException(
                                        resp.statusCode().value(),
                                        "ML worker rejected file: " + (body2.isBlank() ? resp.statusCode().toString() : body2)))))
                .onStatus(org.springframework.http.HttpStatusCode::is5xxServerError, resp ->
                        Mono.error(upstreamFailure(resp.statusCode().value())))
                .bodyToMono(MlReviewResponse.class)
                .timeout(OVERALL_DEADLINE)
                .onErrorMap(java.util.concurrent.TimeoutException.class,
                        ex -> new MlWorkerException("ML worker timed out", ex))
                .onErrorMap(WebClientResponseException.class,
                        ex -> ex.getStatusCode().is4xxClientError()
                                ? new InvalidDiffException(ex.getStatusCode().value(),
                                        "ML worker rejected file: " + ex.getStatusCode())
                                : upstreamFailure(ex.getStatusCode().value()))
                .onErrorMap(java.net.ConnectException.class,
                        ex -> new MlWorkerException("ML worker unavailable", ex))
                .onErrorMap(java.nio.channels.UnresolvedAddressException.class,
                        ex -> new MlWorkerException("ML worker unavailable", ex))
                .onErrorMap(java.io.IOException.class,
                        ex -> new MlWorkerException("ML worker unavailable", ex))
                .onErrorMap(WebClientRequestException.class,
                        ex -> new MlWorkerException("ML worker unavailable", ex, true))
                .doOnError(ex -> log.warn("ML worker file scan failed: {} ({})",
                        ex.getMessage(), ex.getClass().getSimpleName()))
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
     * Lightweight health check. A fallback engine is healthy even when no
     * learned model is loaded, so model availability is not a liveness gate.
     */
    @SuppressWarnings("unchecked")
    public boolean isHealthy() {
        try {
            Map<String, Object> body = client.get()
                    .uri("/ml/health")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .onErrorResume(ex -> Mono.empty())
                    .block(Duration.ofSeconds(3));
            if (body == null) return false;
            Object status = body.get("status");
            return "healthy".equals(status) || "ok".equals(status);
        } catch (Exception ex) {
            log.debug("ML worker health check failed: {}", ex.getMessage());
            return false;
        }
    }

    public List<com.automatedcodereviewtool.dto.MlFinding> reviewFindings(String diff, String language) {
        MlReviewResponse resp = review(diff, language);
        return resp == null || resp.findings() == null ? List.of() : resp.findings();
    }

    // -- Test helpers (package-private) ----------------------------------

    String getMlWorkerUrl() { return mlWorkerUrl; }

    String getMlWorkerSecret() { return mlWorkerSecret; }

    // -- Fallback scoring (used when worker omits the score field) -----

    /**
     * Compute a quality score from a finding list when the ML worker
     * omitted one. Penalty weights match the canonical Python
     * implementation (apps/ml-worker/app/scoring.py):
     *   critical=20, major=10, minor=3, weighted by confidence.
     * Formula: 100 - sum(weight * confidence), clamped to [0, 100],
     * rounded to 2dp. See contracts/quality_score_cases.json for the
     * shared fixture consumed by Python and Java tests.
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
