package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.dto.MlFinding;
import com.automatedcodereviewtool.dto.MlReviewRequest;
import com.automatedcodereviewtool.dto.MlReviewResponse;
import com.automatedcodereviewtool.exception.InvalidDiffException;
import com.automatedcodereviewtool.exception.MlWorkerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Thin client for the internal ML worker (FastAPI, port 8000).
 *
 * <p>The worker is internal-only; we authenticate every request with
 * the {@code X-ML-Worker-Secret} header (per the plan's non-negotiable
 * decision #7). We never call it from outside the API's service mesh.</p>
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

    public MlWorkerService(WebClient.Builder builder,
                           @Value("${app.ml-worker.url}") String mlWorkerUrl,
                           @Value("${app.ml-worker.secret}") String mlWorkerSecret) {
        this.mlWorkerUrl = mlWorkerUrl;
        this.mlWorkerSecret = mlWorkerSecret;
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
                        Mono.error(new MlWorkerException("ML worker unavailable: " + resp.statusCode().value())))
                .bodyToMono(MlReviewResponse.class)
                .timeout(OVERALL_DEADLINE)
                .onErrorMap(java.util.concurrent.TimeoutException.class,
                        ex -> new MlWorkerException("ML worker timed out", ex))
                .onErrorMap(WebClientResponseException.class,
                        ex -> ex.getStatusCode().is4xxClientError()
                                ? new InvalidDiffException(ex.getStatusCode().value(),
                                        "ML worker rejected diff: " + ex.getStatusCode())
                                : new MlWorkerException("ML worker unavailable: " + ex.getStatusCode()))
                .onErrorMap(java.net.ConnectException.class,
                        ex -> new MlWorkerException("ML worker unavailable", ex))
                .onErrorMap(java.nio.channels.UnresolvedAddressException.class,
                        ex -> new MlWorkerException("ML worker unavailable", ex))
                .onErrorMap(java.io.IOException.class,
                        ex -> new MlWorkerException("ML worker unavailable", ex))
                .doOnError(ex -> log.warn("ML worker call failed: {} ({})",
                        ex.getMessage(), ex.getClass().getSimpleName()))
                .block();
    }

    /**
     * Convenience for the file-scan path.
     */
    public MlReviewResponse reviewFile(String content, String language) {
        MlReviewRequest body = MlReviewRequest.file(content, language);
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
                        Mono.error(new MlWorkerException("ML worker unavailable: " + resp.statusCode().value())))
                .bodyToMono(MlReviewResponse.class)
                .timeout(OVERALL_DEADLINE)
                .onErrorMap(java.util.concurrent.TimeoutException.class,
                        ex -> new MlWorkerException("ML worker timed out", ex))
                .onErrorMap(WebClientResponseException.class,
                        ex -> ex.getStatusCode().is4xxClientError()
                                ? new InvalidDiffException(ex.getStatusCode().value(),
                                        "ML worker rejected file: " + ex.getStatusCode())
                                : new MlWorkerException("ML worker unavailable: " + ex.getStatusCode()))
                .onErrorMap(java.net.ConnectException.class,
                        ex -> new MlWorkerException("ML worker unavailable", ex))
                .onErrorMap(java.nio.channels.UnresolvedAddressException.class,
                        ex -> new MlWorkerException("ML worker unavailable", ex))
                .onErrorMap(java.io.IOException.class,
                        ex -> new MlWorkerException("ML worker unavailable", ex))
                .doOnError(ex -> log.warn("ML worker file scan failed: {} ({})",
                        ex.getMessage(), ex.getClass().getSimpleName()))
                .block();
    }

    /**
     * Lightweight health check. Returns {@code true} when the worker
     * reports the model is loaded and responsive; never throws.
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
            Object modelLoaded = body.get("modelLoaded");
            return "ok".equals(status) && Boolean.TRUE.equals(modelLoaded);
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
     * omitted one. Applies a simple penalty model:
     *   critical=20, major=10, minor=5, weighted by confidence.
     * Result is clamped to [0, 100] and rounded to 2dp.
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
            BigDecimal weight = switch (f.severity() == null ? "minor" : f.severity().toLowerCase(Locale.ROOT)) {
                case "critical" -> BigDecimal.valueOf(20);
                case "major" -> BigDecimal.valueOf(10);
                default -> BigDecimal.valueOf(5);
            };
            penalty = penalty.add(weight.multiply(conf));
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