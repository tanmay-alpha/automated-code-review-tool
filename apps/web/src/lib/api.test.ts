import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from "vitest";
import {
  ApiError,
  getDiagnostics,
  getFindingSummary,
  getMe,
  getQualityTrend,
  getRepoPRs,
  getRepoSettings,
  getReviewRuns,
  recordFindingAction,
  revokeApiKey,
  updateRepoSettings,
} from "@/lib/api";
import type { RepoSettings } from "@/lib/types";

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function caught(promise: Promise<unknown>): Promise<ApiError> {
  return promise.then(
    () => {
      throw new Error("expected the request to fail");
    },
    (err: unknown) => {
      if (!(err instanceof ApiError)) throw err;
      return err;
    },
  );
}

let fetchMock: Mock;

beforeEach(() => {
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("api client", () => {
  it("calls same-origin relative URLs with same-origin credentials", async () => {
    fetchMock.mockImplementation(async () => json(200, { content: [] }));

    await getRepoPRs("repo-1", 2, 50);
    await getQualityTrend("repo-1", 7);

    expect(fetchMock.mock.calls[0][0]).toBe("/api/repos/repo-1/prs?page=2&size=50");
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: "GET", credentials: "same-origin" });
    expect(fetchMock.mock.calls[1][0]).toBe("/api/metrics/quality-trend?repo=repo-1&days=7");
    for (const [url] of fetchMock.mock.calls) {
      expect(String(url)).not.toMatch(/^https?:/);
    }
  });

  it("returns undefined for 204 responses", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await expect(revokeApiKey("k1")).resolves.toBeUndefined();
    expect(fetchMock.mock.calls[0][0]).toBe("/api/auth/api-keys/k1");
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: "DELETE" });
  });

  it("uses the API's JSON error message and code", async () => {
    fetchMock.mockResolvedValue(
      json(400, { error: "VALIDATION_FAILED", message: "minConfidence: must be <= 1", status: 400 }),
    );

    const err = await caught(getMe());
    expect(err.status).toBe(400);
    expect(err.code).toBe("VALIDATION_FAILED");
    expect(err.message).toBe("minConfidence: must be <= 1");
  });

  it("does not surface a non-JSON error body", async () => {
    fetchMock.mockResolvedValue(new Response("<html>Bad gateway</html>", { status: 500 }));

    const err = await caught(getMe());
    expect(err.status).toBe(500);
    expect(err.code).toBeNull();
    expect(err.message).toBe("GET /api/auth/me failed with HTTP 500");
  });

  it("posts a triage action with a trimmed reason", async () => {
    const saved = {
      findingId: "f-1",
      status: "false_positive",
      reason: "Test fixture key",
      triagedAt: "2026-11-10T00:00:00Z",
      triagedBy: "octocat",
    };
    fetchMock.mockResolvedValue(json(200, saved));

    await expect(recordFindingAction("f-1", "false_positive", "  Test fixture key  ")).resolves.toEqual(saved);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/scan/action");
    expect(init).toMatchObject({
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
    });
    expect(JSON.parse(init.body as string)).toEqual({
      findingId: "f-1",
      action: "false_positive",
      reason: "Test fixture key",
    });
  });

  it("omits the reason when none is given", async () => {
    fetchMock.mockResolvedValue(json(200, {}));
    await recordFindingAction("f-1", "reopen", "   ");
    expect(JSON.parse(fetchMock.mock.calls[0][1].body as string)).toEqual({
      findingId: "f-1",
      action: "reopen",
    });
  });

  it("surfaces a 404 from /api/scan/action as ApiError", async () => {
    fetchMock.mockResolvedValue(new Response("not found", { status: 404 }));

    const err = await caught(recordFindingAction("f-x", "fix"));
    expect(err.status).toBe(404);
  });

  it("reads and writes repository settings", async () => {
    const settings: RepoSettings = {
      enabledRules: ["SECURITY_SQL_INJECTION"],
      minSeverity: "major",
      minConfidence: 0.5,
      postComments: true,
      inlineComments: false,
      languages: ["python", "java"],
    };
    fetchMock.mockResolvedValueOnce(json(200, settings)).mockResolvedValueOnce(json(200, settings));

    await expect(getRepoSettings("r 1")).resolves.toEqual(settings);
    await expect(updateRepoSettings("r 1", settings)).resolves.toEqual(settings);

    expect(fetchMock.mock.calls[0][0]).toBe("/api/repos/r%201/settings");
    expect(fetchMock.mock.calls[1][0]).toBe("/api/repos/r%201/settings");
    expect(fetchMock.mock.calls[1][1]).toMatchObject({ method: "PUT" });
    expect(JSON.parse(fetchMock.mock.calls[1][1].body as string)).toEqual(settings);
  });

  it("builds the review runs, diagnostics and finding-summary paths", async () => {
    fetchMock.mockImplementation(async () => json(200, []));

    await getReviewRuns("pr-1");
    await getDiagnostics();
    await getFindingSummary("repo-1", 90);

    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual([
      "/api/reviews/pr-1/runs",
      "/api/diagnostics",
      "/api/metrics/finding-summary?repo=repo-1&days=90",
    ]);
  });

  it("refreshes the session once on 401 and retries the request", async () => {
    fetchMock
      .mockResolvedValueOnce(new Response(null, { status: 401 })) // /me
      .mockResolvedValueOnce(new Response(null, { status: 200 })) // /refresh
      .mockResolvedValueOnce(json(200, { id: "u1" })); // /me retry

    await expect(getMe()).resolves.toEqual({ id: "u1" });
    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual([
      "/api/auth/me",
      "/api/auth/refresh",
      "/api/auth/me",
    ]);
    expect(fetchMock.mock.calls[1][1]).toMatchObject({ method: "POST", credentials: "same-origin" });
  });

  it("surfaces the 401 when the refresh is rejected", async () => {
    fetchMock
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(new Response(null, { status: 401 }));

    const err = await caught(getMe());
    expect(err.status).toBe(401);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("shares one refresh between concurrent 401s", async () => {
    let refreshCalls = 0;
    let meCalls = 0;
    fetchMock.mockImplementation(async (url: string) => {
      if (url.endsWith("/api/auth/refresh")) {
        refreshCalls += 1;
        return new Response(null, { status: 200 });
      }
      meCalls += 1;
      return meCalls <= 2
        ? new Response(null, { status: 401 })
        : json(200, { id: "u1" });
    });

    await Promise.all([getMe(), getMe()]);
    expect(refreshCalls).toBe(1);
  });
});
