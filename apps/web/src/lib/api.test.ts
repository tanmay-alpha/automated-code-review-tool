import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from "vitest";
import {
  ApiError,
  getMe,
  getRepoPRs,
  getQualityTrend,
  recordFindingAction,
  revokeApiKey,
} from "@/lib/api";

const BASE = "http://localhost:8080";

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
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
  it("sends cookies and builds the Spring paths/query params", async () => {
    fetchMock.mockImplementation(async () => json(200, { content: [] }));

    await getRepoPRs("repo-1", 2, 50);
    await getQualityTrend("repo-1", 7);

    expect(fetchMock.mock.calls[0][0]).toBe(`${BASE}/api/repos/repo-1/prs?page=2&size=50`);
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: "GET", credentials: "include" });
    expect(fetchMock.mock.calls[1][0]).toBe(
      `${BASE}/api/metrics/quality-trend?repo=repo-1&days=7`,
    );
  });

  it("returns undefined for 204 responses", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await expect(revokeApiKey("k1")).resolves.toBeUndefined();
    expect(fetchMock.mock.calls[0][0]).toBe(`${BASE}/api/auth/api-keys/k1`);
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: "DELETE" });
  });

  it("throws ApiError carrying the HTTP status", async () => {
    fetchMock.mockResolvedValue(new Response("boom", { status: 500 }));

    const err = await getMe().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(500);
    expect((err as ApiError).message).toBe("boom");
  });

  it("posts a finding disposition to /api/scan/action", async () => {
    fetchMock.mockResolvedValue(
      json(200, { findingId: "f-1", action: "dismiss", status: "dismissed" }),
    );

    await expect(recordFindingAction("f-1", "dismiss")).resolves.toEqual({
      findingId: "f-1",
      action: "dismiss",
      status: "dismissed",
    });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe(`${BASE}/api/scan/action`);
    expect(init).toMatchObject({
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json" },
    });
    expect(JSON.parse(init.body as string)).toEqual({ findingId: "f-1", action: "dismiss" });
  });

  it("surfaces a 404 from /api/scan/action as ApiError", async () => {
    fetchMock.mockResolvedValue(new Response("not found", { status: 404 }));

    const err = await recordFindingAction("f-x", "fix").catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(404);
  });

  it("refreshes the session once on 401 and retries the request", async () => {
    fetchMock
      .mockResolvedValueOnce(new Response(null, { status: 401 })) // /me
      .mockResolvedValueOnce(new Response(null, { status: 200 })) // /refresh
      .mockResolvedValueOnce(json(200, { id: "u1" })); // /me retry

    await expect(getMe()).resolves.toEqual({ id: "u1" });
    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual([
      `${BASE}/api/auth/me`,
      `${BASE}/api/auth/refresh`,
      `${BASE}/api/auth/me`,
    ]);
  });

  it("surfaces the 401 when the refresh is rejected", async () => {
    fetchMock
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(new Response(null, { status: 401 }));

    const err = await getMe().catch((e: unknown) => e);
    expect((err as ApiError).status).toBe(401);
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
