const { test } = require("node:test");
const assert = require("node:assert/strict");

const {
  MAX_CONTENT_CHARS,
  ReviewerClientError,
  apiLanguage,
  dedupeFindings,
  exceedsContentLimit,
  mapFindingToDiagnosticData,
  postScan,
} = require("../out/reviewerClient.js");

const payload = {
  content: "const answer = 42;",
  language: "javascript",
  filePath: "src/example.ts",
};

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

test("sends the scan request and bearer API key", async () => {
  let captured;
  const response = await postScan(payload, {
    apiUrl: "https://review.example.test/",
    apiKey: "secret-test-key",
    fetchImpl: async (url, init) => {
      captured = { url, init };
      return jsonResponse({ findings: [], qualityScore: 100, language: payload.language });
    },
  });

  assert.equal(captured.url, "https://review.example.test/api/scan/file");
  assert.equal(captured.init.method, "POST");
  assert.equal(captured.init.redirect, "error");
  assert.equal(captured.init.headers.Authorization, "Bearer secret-test-key");
  assert.equal(captured.init.headers["Content-Type"], "application/json");
  assert.deepEqual(JSON.parse(captured.init.body), payload);
  assert.deepEqual(response.findings, []);
});

test("aborts a request at the configured timeout", async () => {
  const fetchImpl = (_url, init) =>
    new Promise((_resolve, reject) => {
      init.signal.addEventListener("abort", () => {
        const error = new Error("aborted");
        error.name = "AbortError";
        reject(error);
      });
    });

  await assert.rejects(
    postScan(payload, {
      apiUrl: "https://review.example.test",
      apiKey: "test-key",
      timeoutMs: 5,
      fetchImpl,
    }),
    /timed out after 5 ms/,
  );
});

test("reports HTTP status without echoing potentially sensitive response bodies", async (t) => {
  for (const status of [422, 503]) {
    await t.test(String(status), async () => {
      await assert.rejects(
        postScan(payload, {
          apiUrl: "https://review.example.test",
          apiKey: "test-key",
          fetchImpl: async () => new Response("secret-test-key source-code", { status }),
        }),
        (error) => {
          assert.ok(error instanceof ReviewerClientError);
          assert.equal(error.status, status);
          assert.ok(error.message.length < 300);
          assert.ok(!error.message.includes("secret-test-key"));
          assert.ok(!error.message.includes("source-code"));
          return true;
        },
      );
    });
  }
});

test("maps API findings to zero-based diagnostic data", () => {
  const mapped = mapFindingToDiagnosticData({
    antiPattern: "SECURITY_HARDCODED_SECRET",
    severity: "critical",
    confidence: 1.4,
    lineStart: 7,
    lineEnd: 9,
    explanation: "Credential-like value detected.",
  });

  assert.deepEqual(mapped, {
    startLine: 6,
    endLine: 8,
    severity: "error",
    message: "Security Hardcoded Secret (100% confidence): Credential-like value detected.",
    code: "SECURITY_HARDCODED_SECRET",
  });
});

test("deduplicates repeated findings while preserving distinct locations", () => {
  const base = {
    antiPattern: "RELIABILITY_BROAD_EXCEPTION",
    severity: "major",
    confidence: 0.8,
    lineStart: 3,
    lineEnd: 3,
    explanation: "Broad exception handler.",
  };
  const deduped = dedupeFindings([
    { ...base },
    { ...base },
    { ...base, lineStart: 10, lineEnd: 10 },
  ]);

  assert.deepEqual(deduped.map((finding) => finding.lineStart), [3, 10]);
});

test("maps VS Code language ids to the languages the API accepts", () => {
  assert.equal(apiLanguage("python"), "python");
  assert.equal(apiLanguage("typescriptreact"), "javascript");
  assert.equal(apiLanguage("java"), "java");
  assert.equal(apiLanguage("markdown"), undefined);
});

test("enforces the API's 200,000-character content limit", () => {
  assert.equal(exceedsContentLimit("a".repeat(MAX_CONTENT_CHARS)), false);
  assert.equal(exceedsContentLimit("a".repeat(MAX_CONTENT_CHARS + 1)), true);
});

test("rejects insecure and credential-bearing endpoints before sending a key", async () => {
  for (const apiUrl of [
    "http://remote.example.test",
    "https://user:password@review.example.test",
    "https://review.example.test?token=secret",
    "https://review.example.test#fragment",
    "file:///tmp/review",
  ]) {
    await assert.rejects(postScan(payload, {
      apiUrl,
      apiKey: "test-key",
      fetchImpl: async () => { assert.fail("must reject before fetching"); },
    }), /API URL must use HTTPS/);
  }
});

test("allows loopback HTTP for local development", async () => {
  for (const apiUrl of ["http://localhost:8080", "http://127.0.0.1:8080", "http://[::1]:8080"]) {
    const result = await postScan(payload, {
      apiUrl,
      apiKey: "test-key",
      fetchImpl: async () => jsonResponse({ findings: [] }),
    });
    assert.deepEqual(result.findings, []);
  }
});

test("real HTTP redirects cannot forward source code to another endpoint", async () => {
  const { createServer } = require("node:http");
  let redirectedRequests = 0;
  const destination = createServer((_req, res) => {
    redirectedRequests++;
    res.writeHead(200, { "content-type": "application/json" });
    res.end('{"findings":[]}');
  });
  const listen = (server) => new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const close = (server) => new Promise((resolve) => server.close(resolve));
  await listen(destination);
  const origin = createServer((_req, res) => {
    res.writeHead(307, { location: `http://127.0.0.1:${destination.address().port}/stolen` });
    res.end();
  });
  await listen(origin);
  try {
    await assert.rejects(postScan(payload, {
      apiUrl: `http://127.0.0.1:${origin.address().port}`,
      apiKey: "real-redirect-test-key",
    }));
    assert.equal(redirectedRequests, 0);
  } finally {
    await Promise.all([close(origin), close(destination)]);
  }
});
