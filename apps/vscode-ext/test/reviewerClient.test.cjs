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

test("reports both 4xx and 5xx responses with bounded response text", async (t) => {
  for (const status of [422, 503]) {
    await t.test(String(status), async () => {
      await assert.rejects(
        postScan(payload, {
          apiUrl: "https://review.example.test",
          apiKey: "test-key",
          fetchImpl: async () => new Response("x".repeat(500), { status }),
        }),
        (error) => {
          assert.ok(error instanceof ReviewerClientError);
          assert.equal(error.status, status);
          assert.ok(error.message.length < 300);
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
