package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.dto.MlReviewResponse;
import com.automatedcodereviewtool.exception.InvalidDiffException;
import com.automatedcodereviewtool.exception.MlWorkerException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MlWorkerService tests.
 *
 * <p>Uses {@link MockWebServer} (OkHttp) to run a real local HTTP
 * server and assert the outgoing request shape + response handling.
 * No network or ML worker process required.</p>
 *
 * <p>The {@code X-ML-Worker-Secret} header is checked on every test
 * that returns a 2xx — getting it wrong would fail authentication in
 * production and is the single most likely integration bug.</p>
 */
class MlWorkerServiceTest {

    private MockWebServer server;
    private MlWorkerService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        WebClient.Builder builder = WebClient.builder();
        // Connect to the mock server's URL; rely on default timeouts in tests.
        service = new MlWorkerService(
                builder,
                server.url("/").toString().replaceAll("/$", ""),
                "test-secret");
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void review_returnsParsedResponse_on2xx() throws Exception {
        // Same shape as apps/ml-worker/app/schemas.py ReviewResponse.
        String json = """
                {
                  "findings": [
                    {
                      "filePath": "src/Foo.java",
                      "hunkHash": "abc",
                      "lineStart": 12,
                      "lineEnd": 14,
                      "antiPattern": "SECURITY_HARDCODED_SECRET",
                      "category": "SECURITY",
                      "severity": "critical",
                      "confidence": 0.92,
                      "explanation": "hard-coded credential"
                    }
                  ],
                  "qualityScore": 81.6,
                  "processingTimeMs": 12,
                  "windowsProcessed": 1,
                  "engine": "fallback",
                  "modelVersion": "rules-v1",
                  "taxonomyVersion": "1.0.0"
                }
                """;
        server.enqueue(new MockResponse()
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setResponseCode(200)
                .setBody(json));

        MlReviewResponse out = service.review("diff --git a/x b/x", "java");

        assertThat(out).isNotNull();
        assertThat(out.findings()).hasSize(1);
        assertThat(out.findings().get(0).antiPattern()).isEqualTo("SECURITY_HARDCODED_SECRET");
        assertThat(out.findings().get(0).filePath()).isEqualTo("src/Foo.java");
        assertThat(out.findings().get(0).lineStart()).isEqualTo(12);
        assertThat(out.qualityScore()).isEqualByComparingTo("81.6");
        assertThat(out.engine()).isEqualTo("fallback");

        RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).as("server should have received the request").isNotNull();
        assertThat(req.getPath()).isEqualTo("/ml/review");
        assertThat(req.getHeader("X-ML-Worker-Secret")).isEqualTo("test-secret");
        assertThat(req.getHeader(HttpHeaders.CONTENT_TYPE))
                .contains(MediaType.APPLICATION_JSON_VALUE);
    }

    @Test
    void review_throwsInvalidDiff_on400() {
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .setBody("not a real diff"));

        assertThatThrownBy(() -> service.review("garbage", "java"))
                .isInstanceOf(InvalidDiffException.class)
                .hasMessageContaining("rejected diff");
    }

    @Test
    void review_throwsInvalidDiff_on422_withoutEchoingTheWorkerBody() {
        // FastAPI's default 422 echoes the request, i.e. the whole diff.
        server.enqueue(new MockResponse()
                .setResponseCode(422)
                .setBody("{\"detail\":[{\"input\":\"SECRET_SOURCE_LINE\"}]}"));

        assertThatThrownBy(() -> service.review("SECRET_SOURCE_LINE", "java"))
                .isInstanceOf(InvalidDiffException.class)
                .hasMessage("ML worker rejected diff: HTTP 422");
    }

    @Test
    void review_throwsMlWorkerException_on500() {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("internal error"));

        assertThatThrownBy(() -> service.review("diff", "java"))
                .isInstanceOf(MlWorkerException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    void review_throwsMlWorkerException_on503() {
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse()
                    .setResponseCode(503)
                    .setBody("down"));
        }

        assertThatThrownBy(() -> service.review("diff", "java"))
                .isInstanceOf(MlWorkerException.class);
    }

    @Test
    void review_retriesRetryable503_thenSucceeds() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("temporary"));
        server.enqueue(new MockResponse()
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setResponseCode(200)
                .setBody("{\"findings\":[],\"qualityScore\":100}"));

        MlReviewResponse response = service.review("diff", "java");

        assertThat(response.qualityScore()).isEqualByComparingTo("100");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void review_doesNotRetry400() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("bad diff"));

        assertThatThrownBy(() -> service.review("bad", "java"))
                .isInstanceOf(InvalidDiffException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    /** Scenario: the worker answers 2xx with something that is not a review. */
    @Test
    void review_malformedSuccessBody_isAnMlWorkerFailureWithoutImmediateRetry() {
        server.enqueue(new MockResponse()
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setResponseCode(200)
                .setBody("{\"findings\": \"not-a-list\""));

        assertThatThrownBy(() -> service.review("diff", "java"))
                .isInstanceOf(MlWorkerException.class)
                .hasMessage("ML worker returned a malformed response");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void review_parsesExplainabilityFields() {
        server.enqueue(new MockResponse()
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setResponseCode(200)
                .setBody("""
                        {"findings":[{"filePath":"a.py","lineStart":2,"antiPattern":"X","confidence":0.5,
                          "evidence":"KEY = \\"<REDACTED_SECRET>\\"","remediation":"Use env vars.",
                          "caveat":"Fixtures are fine."}],
                         "qualityScore":90,"engine":"fallback"}
                        """));

        MlReviewResponse response = service.review("diff", "python");

        assertThat(response.findings()).singleElement().satisfies(f -> {
            assertThat(f.evidence()).isEqualTo("KEY = \"<REDACTED_SECRET>\"");
            assertThat(f.remediation()).isEqualTo("Use env vars.");
            assertThat(f.caveat()).isEqualTo("Fixtures are fine.");
        });
    }

    @Test
    void reviewFile_callsSameEndpoint_asReview() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setResponseCode(200)
                .setBody("{\"findings\":[],\"qualityScore\":0.9}"));

        MlReviewResponse out = service.reviewFile("public class A {}", "java", "src/A.java");

        assertThat(out).isNotNull();
        assertThat(out.findings()).isEmpty();
        assertThat(out.qualityScore()).isEqualByComparingTo("0.9");

        RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).isEqualTo("/ml/review");
        assertThat(req.getBody().readUtf8()).contains("\"filePath\":\"src/A.java\"");
    }
}
