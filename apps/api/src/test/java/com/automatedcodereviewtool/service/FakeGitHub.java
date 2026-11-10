package com.automatedcodereviewtool.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateful stand-in for the GitHub REST endpoints the review pipeline uses
 * (diff, PR head, issue comments, PR reviews), served by MockWebServer.
 * List endpoints paginate with a {@code Link} header like GitHub does.
 * Faults are injected per {@code "METHOD kind"} key, e.g. {@code "POST comment"}.
 */
final class FakeGitHub extends Dispatcher {

    private static final Pattern PR = Pattern.compile("^/repos/([^/]+)/([^/]+)/pulls/(\\d+)$");
    private static final Pattern ISSUE_COMMENTS = Pattern.compile("^/repos/([^/]+)/([^/]+)/issues/(\\d+)/comments$");
    private static final Pattern COMMENT = Pattern.compile("^/repos/([^/]+)/([^/]+)/issues/comments/(\\d+)$");
    private static final Pattern REVIEWS = Pattern.compile("^/repos/([^/]+)/([^/]+)/pulls/(\\d+)/reviews$");

    /** A fault: either a canned response, or "apply the write, then drop the connection". */
    record Fault(MockResponse response, boolean applyThenDisconnect) {
        static Fault respond(MockResponse response) {
            return new Fault(response, false);
        }

        static Fault lostAck() {
            return new Fault(null, true);
        }
    }

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong ids = new AtomicLong(1000);

    /** Diff served for the current head. */
    volatile java.util.function.Function<String, String> diffForHead = head -> "";
    volatile String headSha = "";
    volatile long botUserId;

    final List<Map<String, Object>> comments = Collections.synchronizedList(new ArrayList<>());
    final List<Map<String, Object>> reviews = Collections.synchronizedList(new ArrayList<>());
    final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final Map<String, List<Fault>> faults = new ConcurrentHashMap<>();
    /** Runs when the diff is served, e.g. to simulate a push racing the review. */
    volatile Runnable onDiffServed = () -> { };
    volatile Runnable onCommentsListed = () -> { };

    void reset(long botUserId, String headSha, java.util.function.Function<String, String> diffForHead) {
        this.botUserId = botUserId;
        this.headSha = headSha;
        this.diffForHead = diffForHead;
        comments.clear();
        reviews.clear();
        requests.clear();
        calls.clear();
        faults.clear();
        onDiffServed = () -> { };
        onCommentsListed = () -> { };
    }

    /** The next {@code count} matching calls fail with {@code fault}. */
    void fail(String key, int count, Fault fault) {
        List<Fault> queue = faults.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()));
        for (int i = 0; i < count; i++) {
            queue.add(fault);
        }
    }

    int calls(String key) {
        AtomicInteger n = calls.get(key);
        return n == null ? 0 : n.get();
    }

    /** A comment as GitHub would store it. */
    Map<String, Object> seedComment(long authorId, String body) {
        Map<String, Object> comment = new LinkedHashMap<>();
        long id = ids.incrementAndGet();
        comment.put("id", id);
        comment.put("html_url", "https://github.example/comment/" + id);
        comment.put("body", body);
        comment.put("user", Map.of("id", authorId, "login", "user-" + authorId));
        comments.add(comment);
        return comment;
    }

    @Override
    public MockResponse dispatch(RecordedRequest request) {
        String method = request.getMethod();
        String target = request.getPath() == null ? "" : request.getPath();
        String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
        requests.add(method + " " + target);
        try {
            Matcher m;
            if ((m = PR.matcher(path)).matches() && "GET".equals(method)) {
                boolean wantsDiff = String.valueOf(request.getHeader("Accept")).contains("diff");
                int number = Integer.parseInt(m.group(3));
                String key = wantsDiff ? "GET diff" : "GET head";
                return handle(key, () -> {
                    if (wantsDiff) {
                        String diff = diffForHead.apply(headSha);
                        onDiffServed.run();
                        return new MockResponse().setBody(diff);
                    }
                    return jsonResponse(200, Map.of("number", number,
                            "head", Map.of("sha", headSha)));
                });
            }
            if ((m = ISSUE_COMMENTS.matcher(path)).matches()) {
                if ("GET".equals(method)) {
                    return handle("GET comments", () -> {
                        MockResponse response = page(comments, target);
                        onCommentsListed.run();
                        return response;
                    });
                }
                if ("POST".equals(method)) {
                    String body = bodyField(request);
                    return handle("POST comment", () -> jsonResponse(201, seedComment(botUserId, body)));
                }
            }
            if ((m = COMMENT.matcher(path)).matches() && "PATCH".equals(method)) {
                long id = Long.parseLong(m.group(3));
                String body = bodyField(request);
                return handle("PATCH comment", () -> {
                    synchronized (comments) {
                        for (Map<String, Object> c : comments) {
                            if (((Number) c.get("id")).longValue() == id) {
                                c.put("body", body);
                                return jsonResponse(200, c);
                            }
                        }
                    }
                    return jsonResponse(404, Map.of("message", "Not Found"));
                });
            }
            if ((m = REVIEWS.matcher(path)).matches()) {
                if ("GET".equals(method)) {
                    return handle("GET reviews", () -> page(reviews, target));
                }
                if ("POST".equals(method)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = json.readValue(request.getBody().readUtf8(), Map.class);
                    return handle("POST review", () -> {
                        Map<String, Object> review = new LinkedHashMap<>(payload);
                        review.put("id", ids.incrementAndGet());
                        review.put("user", Map.of("id", botUserId));
                        reviews.add(review);
                        return jsonResponse(200, review);
                    });
                }
            }
            return jsonResponse(404, Map.of("message", "Not Found"));
        } catch (Exception ex) {
            return new MockResponse().setResponseCode(500).setBody(String.valueOf(ex));
        }
    }

    private MockResponse handle(String key, ThrowingSupplier<MockResponse> success) throws Exception {
        calls.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        List<Fault> queue = faults.get(key);
        Fault fault = null;
        if (queue != null) {
            synchronized (queue) {
                if (!queue.isEmpty()) {
                    fault = queue.remove(0);
                }
            }
        }
        if (fault == null) {
            return success.get();
        }
        if (fault.applyThenDisconnect()) {
            success.get();
            return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
        }
        return fault.response();
    }

    private MockResponse page(List<Map<String, Object>> items, String target) throws Exception {
        Map<String, String> query = query(target);
        int perPage = Integer.parseInt(query.getOrDefault("per_page", "30"));
        int page = Integer.parseInt(query.getOrDefault("page", "1"));
        List<Map<String, Object>> snapshot;
        synchronized (items) {
            snapshot = new ArrayList<>(items);
        }
        int from = Math.min(snapshot.size(), (page - 1) * perPage);
        int to = Math.min(snapshot.size(), from + perPage);
        MockResponse response = jsonResponse(200, snapshot.subList(from, to));
        if (to < snapshot.size()) {
            response.addHeader("Link", "<https://api.github.example/next?page=" + (page + 1)
                    + ">; rel=\"next\", <https://api.github.example/last>; rel=\"last\"");
        }
        return response;
    }

    private static Map<String, String> query(String target) {
        Map<String, String> out = new LinkedHashMap<>();
        int q = target.indexOf('?');
        if (q < 0) return out;
        for (String pair : target.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) out.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return out;
    }

    private String bodyField(RecordedRequest request) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = json.readValue(request.getBody().readUtf8(), Map.class);
        return (String) payload.get("body");
    }

    private MockResponse jsonResponse(int status, Object body) throws Exception {
        return new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(json.writeValueAsString(body));
    }

    static MockResponse status(int status, String... headers) {
        MockResponse response = new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"message\":\"status " + status + "\"}");
        for (int i = 0; i + 1 < headers.length; i += 2) {
            response.setHeader(headers[i], headers[i + 1]);
        }
        return response;
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
