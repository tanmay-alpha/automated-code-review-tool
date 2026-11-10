// @vitest-environment node
import http from "node:http";
import type { AddressInfo } from "node:net";
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";
import {
  DEFAULT_MAX_BODY_BYTES,
  proxyApiRequest,
  resolveProxyConfig,
} from "@/lib/apiProxy";

const ENV = { API_INTERNAL_URL: "http://api.internal:8080", NODE_ENV: "production" };

function captureFetch(response: Response | (() => Response) = new Response(null, { status: 204 })) {
  const calls: { url: string; init: RequestInit }[] = [];
  const fetchImpl = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
    calls.push({ url: String(url), init: init ?? {} });
    // Drain a streamed body like a real upstream would.
    if (init?.body instanceof ReadableStream) {
      await new Response(init.body).arrayBuffer();
    }
    return typeof response === "function" ? response() : response;
  });
  return { calls, fetchImpl: fetchImpl as unknown as typeof fetch };
}

describe("resolveProxyConfig", () => {
  it("accepts a full origin and strips the trailing slash", () => {
    const r = resolveProxyConfig({ API_INTERNAL_URL: "https://api.example.test/" });
    expect(r).toEqual({
      ok: true,
      config: { upstream: "https://api.example.test", maxBodyBytes: DEFAULT_MAX_BODY_BYTES, timeoutMs: 30_000 },
    });
  });

  it("accepts a Render hostport reference", () => {
    const r = resolveProxyConfig({ API_INTERNAL_URL: "acrt-api-x1y2:8080", NODE_ENV: "production" });
    expect(r.ok && r.config.upstream).toBe("http://acrt-api-x1y2:8080");
  });

  it("requires API_INTERNAL_URL in production but defaults in development", () => {
    expect(resolveProxyConfig({ NODE_ENV: "production" }).ok).toBe(false);
    const dev = resolveProxyConfig({ NODE_ENV: "development" });
    expect(dev.ok && dev.config.upstream).toBe("http://localhost:8080");
  });

  it.each([
    "ftp://api:21",
    "http://user:pw@api:8080",
    "http://api:8080/prefix",
    "http://api:8080?x=1",
    "http://[bad",
  ])("rejects %s", (value) => {
    expect(resolveProxyConfig({ API_INTERNAL_URL: value }).ok).toBe(false);
  });

  it("validates API_PROXY_MAX_BODY_BYTES", () => {
    const ok = resolveProxyConfig({ ...ENV, API_PROXY_MAX_BODY_BYTES: "2048" });
    expect(ok.ok && ok.config.maxBodyBytes).toBe(2048);
    expect(resolveProxyConfig({ ...ENV, API_PROXY_MAX_BODY_BYTES: "2kb" }).ok).toBe(false);
    expect(resolveProxyConfig({ ...ENV, API_PROXY_MAX_BODY_BYTES: "0" }).ok).toBe(false);
  });
});

describe("proxyApiRequest", () => {
  it("forwards method, path, query and browser security headers", async () => {
    const { calls, fetchImpl } = captureFetch();
    const req = new Request("https://web.example.test/api/repos/r-1/prs?page=2&size=20", {
      method: "GET",
      headers: {
        cookie: "__Host-accessToken=a; __Host-refreshToken=r",
        origin: "https://web.example.test",
        referer: "https://web.example.test/repo/r-1",
        "sec-fetch-site": "same-origin",
        "sec-fetch-mode": "cors",
        authorization: "Bearer k",
        "x-forwarded-for": "203.0.113.7, 10.1.2.3",
        "true-client-ip": "203.0.113.7",
        accept: "application/json",
      },
    });

    const res = await proxyApiRequest(req, { env: ENV, fetchImpl });

    expect(res.status).toBe(204);
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe("http://api.internal:8080/api/repos/r-1/prs?page=2&size=20");
    expect(calls[0].init.method).toBe("GET");
    expect(calls[0].init.redirect).toBe("manual");
    expect(calls[0].init.body).toBeUndefined();
    const h = new Headers(calls[0].init.headers);
    expect(h.get("cookie")).toBe("__Host-accessToken=a; __Host-refreshToken=r");
    expect(h.get("origin")).toBe("https://web.example.test");
    expect(h.get("referer")).toBe("https://web.example.test/repo/r-1");
    expect(h.get("sec-fetch-site")).toBe("same-origin");
    expect(h.get("sec-fetch-mode")).toBe("cors");
    expect(h.get("authorization")).toBe("Bearer k");
    expect(h.get("x-forwarded-for")).toBe("203.0.113.7, 10.1.2.3");
    expect(h.get("true-client-ip")).toBe("203.0.113.7");
    expect(h.get("x-forwarded-host")).toBe("web.example.test");
    expect(h.get("x-forwarded-proto")).toBe("https");
  });

  it("never forwards hop-by-hop, Host or Next.js internal headers", async () => {
    const { calls, fetchImpl } = captureFetch();
    const req = new Request("http://localhost:3000/api/auth/me", {
      headers: {
        connection: "keep-alive, x-secret-hop",
        "keep-alive": "timeout=5",
        "x-secret-hop": "1",
        "proxy-authorization": "Basic Zm9vOmJhcg==",
        te: "trailers",
        upgrade: "websocket",
        "x-middleware-subrequest": "middleware",
        "accept-encoding": "gzip",
      },
    });
    // `host` is a forbidden header for Request in some runtimes; set it directly.
    req.headers.set("host", "localhost:3000");

    await proxyApiRequest(req, { env: ENV, fetchImpl });

    const h = new Headers(calls[0].init.headers);
    for (const name of [
      "connection",
      "keep-alive",
      "x-secret-hop",
      "proxy-authorization",
      "te",
      "upgrade",
      "x-middleware-subrequest",
      "host",
      "accept-encoding",
    ]) {
      expect(h.has(name), name).toBe(false);
    }
    expect(h.get("x-forwarded-host")).toBe("localhost:3000");
    expect(h.get("x-forwarded-proto")).toBe("http");
  });

  it("prefers forwarding headers set by a fronting load balancer", async () => {
    const { calls, fetchImpl } = captureFetch();
    const req = new Request("http://10.0.0.5:3000/api/auth/me", {
      headers: { "x-forwarded-host": "web.example.test", "x-forwarded-proto": "https" },
    });
    await proxyApiRequest(req, { env: ENV, fetchImpl });
    const h = new Headers(calls[0].init.headers);
    expect(h.get("x-forwarded-host")).toBe("web.example.test");
    expect(h.get("x-forwarded-proto")).toBe("https");
  });

  it("passes every Set-Cookie and the Location of a redirect through unchanged", async () => {
    const upstreamHeaders = new Headers();
    upstreamHeaders.append("location", "https://web.example.test/dashboard");
    upstreamHeaders.append("set-cookie", "__Host-accessToken=a1; Path=/; Secure; HttpOnly; SameSite=Lax");
    upstreamHeaders.append("set-cookie", "__Host-refreshToken=r1; Path=/; Secure; HttpOnly; SameSite=Lax");
    upstreamHeaders.append("set-cookie", "__Host-oauth_state=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax");
    const { fetchImpl } = captureFetch(new Response(null, { status: 302, headers: upstreamHeaders }));

    const res = await proxyApiRequest(
      new Request("https://web.example.test/api/auth/callback?code=c&state=s"),
      { env: ENV, fetchImpl },
    );

    expect(res.status).toBe(302);
    expect(res.headers.get("location")).toBe("https://web.example.test/dashboard");
    expect(res.headers.getSetCookie()).toEqual([
      "__Host-accessToken=a1; Path=/; Secure; HttpOnly; SameSite=Lax",
      "__Host-refreshToken=r1; Path=/; Secure; HttpOnly; SameSite=Lax",
      "__Host-oauth_state=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax",
    ]);
  });

  it("returns the upstream status and body and drops decoded-body headers", async () => {
    const { fetchImpl } = captureFetch(
      new Response(JSON.stringify({ error: "CSRF_ORIGIN_REJECTED" }), {
        status: 403,
        headers: {
          "content-type": "application/json",
          "content-encoding": "gzip",
          "content-length": "999",
          "transfer-encoding": "chunked",
          "x-request-id": "abc",
        },
      }),
    );
    const res = await proxyApiRequest(
      new Request("https://web.example.test/api/scan/action", { method: "POST", body: "{}" }),
      { env: ENV, fetchImpl },
    );
    expect(res.status).toBe(403);
    expect(await res.json()).toEqual({ error: "CSRF_ORIGIN_REJECTED" });
    expect(res.headers.get("content-type")).toBe("application/json");
    expect(res.headers.get("x-request-id")).toBe("abc");
    expect(res.headers.has("content-encoding")).toBe(false);
    expect(res.headers.has("content-length")).toBe(false);
    expect(res.headers.has("transfer-encoding")).toBe(false);
  });

  it("streams the request body with its content type", async () => {
    let received = "";
    const fetchImpl = (async (_url: string, init?: RequestInit) => {
      expect(init?.body).toBeInstanceOf(ReadableStream);
      expect(init && "duplex" in init ? init.duplex : undefined).toBe("half");
      expect(new Headers(init?.headers).get("content-type")).toBe("application/json");
      received = await new Response(init?.body).text();
      return new Response("{}", { status: 200, headers: { "content-type": "application/json" } });
    }) as unknown as typeof fetch;
    const body = JSON.stringify({ findingId: "f-1", action: "false_positive", reason: "test fixture" });
    const res = await proxyApiRequest(
      new Request("https://web.example.test/api/scan/action", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body,
      }),
      { env: ENV, fetchImpl },
    );
    expect(res.status).toBe(200);
    expect(received).toBe(body);
  });

  it("rejects a declared body over the cap with 413 without calling upstream", async () => {
    const { calls, fetchImpl } = captureFetch();
    const res = await proxyApiRequest(
      new Request("https://web.example.test/api/scan/file", {
        method: "POST",
        headers: { "content-length": "11" },
        body: "x".repeat(11),
      }),
      { env: { ...ENV, API_PROXY_MAX_BODY_BYTES: "10" }, fetchImpl },
    );
    expect(res.status).toBe(413);
    expect(calls).toHaveLength(0);
  });

  it("rejects a streamed body that exceeds the cap with 413", async () => {
    const { fetchImpl } = captureFetch(new Response("{}"));
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new Uint8Array(8));
        controller.enqueue(new Uint8Array(8));
        controller.close();
      },
    });
    const res = await proxyApiRequest(
      new Request("https://web.example.test/api/scan/file", {
        method: "POST",
        body: stream,
        duplex: "half",
      } as RequestInit),
      { env: { ...ENV, API_PROXY_MAX_BODY_BYTES: "10" }, fetchImpl },
    );
    expect(res.status).toBe(413);
    expect((await res.json()).error).toBe("PAYLOAD_TOO_LARGE");
  });

  it("returns 502 JSON without internals when the upstream is unreachable", async () => {
    const log = vi.fn();
    const fetchImpl = (async () => {
      throw new TypeError("fetch failed: connect ECONNREFUSED 10.0.0.9:8080 api.internal");
    }) as unknown as typeof fetch;
    const res = await proxyApiRequest(new Request("https://web.example.test/api/auth/me"), {
      env: ENV,
      fetchImpl,
      log,
    });
    expect(res.status).toBe(502);
    const text = await res.text();
    expect(JSON.parse(text).error).toBe("UPSTREAM_UNAVAILABLE");
    expect(text).not.toMatch(/ECONNREFUSED|api\.internal|10\.0\.0\.9/);
    expect(log).toHaveBeenCalledOnce();
  });

  it("returns 500 JSON without the reason when the upstream is not configured", async () => {
    const log = vi.fn();
    const res = await proxyApiRequest(new Request("https://web.example.test/api/auth/me"), {
      env: { NODE_ENV: "production" },
      log,
    });
    expect(res.status).toBe(500);
    const text = await res.text();
    expect(JSON.parse(text).error).toBe("PROXY_MISCONFIGURED");
    expect(text).not.toContain("API_INTERNAL_URL");
    expect(log.mock.calls[0][0]).toContain("API_INTERNAL_URL");
  });

  it("only proxies /api/ paths", async () => {
    const { calls, fetchImpl } = captureFetch();
    const res = await proxyApiRequest(new Request("https://web.example.test/apix/secret"), {
      env: ENV,
      fetchImpl,
    });
    expect(res.status).toBe(404);
    expect(calls).toHaveLength(0);
  });
});

// End-to-end against a real HTTP upstream through the platform `fetch`, which
// decodes gzip, folds headers and handles streamed bodies for real.
describe("proxyApiRequest against a real upstream", () => {
  let server: http.Server;
  let origin = "";
  const seen: { method?: string; url?: string; headers?: http.IncomingHttpHeaders; body?: string } = {};

  beforeAll(async () => {
    server = http.createServer((req, res) => {
      const chunks: Buffer[] = [];
      req.on("data", (c: Buffer) => chunks.push(c));
      req.on("end", () => {
        seen.method = req.method;
        seen.url = req.url;
        seen.headers = req.headers;
        seen.body = Buffer.concat(chunks).toString("utf8");
        if (req.url?.startsWith("/api/auth/refresh")) {
          res.writeHead(200, [
            ["Set-Cookie", "accessToken=new-a; Path=/; HttpOnly; SameSite=Lax"],
            ["Set-Cookie", "refreshToken=new-r; Path=/; HttpOnly; SameSite=Lax"],
          ]);
          res.end();
          return;
        }
        if (req.url?.startsWith("/api/auth/github")) {
          res.writeHead(302, { Location: "https://github.com/login/oauth/authorize?client_id=x&state=s" });
          res.end();
          return;
        }
        res.writeHead(200, { "content-type": "application/json" });
        res.end(JSON.stringify({ ok: true }));
      });
    });
    const listening = Promise.withResolvers<void>();
    server.listen(0, "127.0.0.1", () => listening.resolve());
    await listening.promise;
    origin = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  });

  afterAll(async () => {
    const closed = Promise.withResolvers<void>();
    server.close(() => closed.resolve());
    await closed.promise;
  });

  it("returns both rotated cookies from a refresh", async () => {
    const res = await proxyApiRequest(
      new Request("http://localhost:3000/api/auth/refresh", {
        method: "POST",
        headers: { cookie: "refreshToken=old-r", origin: "http://localhost:3000" },
      }),
      { env: { API_INTERNAL_URL: origin } },
    );
    expect(res.status).toBe(200);
    expect(res.headers.getSetCookie()).toEqual([
      "accessToken=new-a; Path=/; HttpOnly; SameSite=Lax",
      "refreshToken=new-r; Path=/; HttpOnly; SameSite=Lax",
    ]);
    expect(seen.method).toBe("POST");
    expect(seen.headers?.cookie).toBe("refreshToken=old-r");
    expect(seen.headers?.origin).toBe("http://localhost:3000");
  });

  it("does not follow redirects", async () => {
    const res = await proxyApiRequest(new Request("http://localhost:3000/api/auth/github"), {
      env: { API_INTERNAL_URL: origin },
    });
    expect(res.status).toBe(302);
    expect(res.headers.get("location")).toBe(
      "https://github.com/login/oauth/authorize?client_id=x&state=s",
    );
  });

  it("streams a JSON body to the upstream byte for byte", async () => {
    const payload = JSON.stringify({ content: "é".repeat(5000), language: "python" });
    const res = await proxyApiRequest(
      new Request("http://localhost:3000/api/scan/file?x=1", {
        method: "PUT",
        headers: { "content-type": "application/json" },
        body: payload,
      }),
      { env: { API_INTERNAL_URL: origin } },
    );
    expect(await res.json()).toEqual({ ok: true });
    expect(seen.method).toBe("PUT");
    expect(seen.url).toBe("/api/scan/file?x=1");
    expect(seen.body).toBe(payload);
    expect(seen.headers?.["content-type"]).toBe("application/json");
  });
});
