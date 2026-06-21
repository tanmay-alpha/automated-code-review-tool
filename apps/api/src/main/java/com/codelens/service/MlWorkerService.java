package com.codelens.service;

import com.codelens.dto.MlFinding;
import com.codelens.dto.MlReviewResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;

import java.util.List;
import java.util.Map;

/**
 * Thin client for the internal ML worker (FastAPI, port 8000).
 *
 * <p>The worker is internal-only; we authenticate every request with
 * the {@code X-ML-Worker-Secret} header (per the plan's non-negotiable
 * decision #7). We never call it from outside the API's service mesh.</p>
 */
@Service
public class MlWorkerService {

    private final WebClient client;
    private final String secret;

    public MlWorkerService(WebClient.Builder builder,
                           @Value("${app.ml-worker.url}") String baseUrl,
                           @Value("${app.ml-worker.secret}") String secret) {
        this.client = builder.baseUrl(baseUrl).build();
        this.secret = secret;
    }

    /**
     * Call {@code POST /ml/review} and return the parsed response. If the
     * worker is unreachable, throws — the caller is responsible for
     * catching and marking the PR as failed.
     */
    public MlReviewResponse review(String diff, String language) {
        return client.post()
                .uri("/ml/review")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("X-ML-Worker-Secret", secret)
                .bodyValue(Map.of(
                        "diff", diff,
                        "language", language,
                        "mode", "diff"))
                .retrieve()
                .bodyToMono(MlReviewResponse.class)
                .onErrorMap(WebClientException.class,
                        ex -> new IllegalStateException("ML worker call failed", ex))
                .block();
    }

    public List<MlFinding> reviewFindings(String diff, String language) {
        MlReviewResponse resp = review(diff, language);
        return resp == null || resp.findings() == null ? List.of() : resp.findings();
    }
}
