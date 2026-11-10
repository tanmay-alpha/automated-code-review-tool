// =============================================================================
// Same-origin API proxy (server-only).
//
// The dashboard and the Spring API run as separate services. Calling the API
// cross-origin cannot authenticate on hosts such as `*.onrender.com`: that
// suffix is on the Public Suffix List, so the two services are cross-SITE and
// the browser never attaches the API's SameSite=Lax auth cookies to `fetch`.
// Instead the browser calls `/api/*` on the web origin and this module forwards
// each request to `API_INTERNAL_URL`, so the cookies belong to the web origin.
//
// Forwarded unchanged: method, path, query, body (streamed, size-capped) and
// every end-to-end request header — notably Cookie, Origin, Referer,
// Sec-Fetch-*, Content-Type, Authorization, X-Forwarded-For, True-Client-IP
// and GitHub's webhook headers. X-Forwarded-Proto/Host describe the browser-
// facing origin. Returned unchanged: status, body, every Set-Cookie and
// Location (redirects are never followed). Hop-by-hop headers are dropped in
// both directions. Upstream failures become a generic JSON error so internal
// hostnames and stack traces never reach the browser.
// =============================================================================

/** Matches the API's default request body limit (APP_MAX_BODY_SIZE). */
export const DEFAULT_MAX_BODY_BYTES = 1_048_576;
/** OAuth callbacks exchange a code with GitHub, so allow a generous budget. */
export const UPSTREAM_TIMEOUT_MS = 30_000;
/** Used only outside production so `next dev` works against a local API. */
export const DEV_DEFAULT_UPSTREAM = "http://localhost:8080";

export interface ProxyConfig {
  /** Upstream origin, e.g. `http://api:8080`, without a trailing slash. */
  upstream: string;
  maxBodyBytes: number;
  timeoutMs: number;
}

export interface ProxyEnv {
  API_INTERNAL_URL?: string;
  API_PROXY_MAX_BODY_BYTES?: string;
  NODE_ENV?: string;
}

/** RFC 9110 §7.6.1 hop-by-hop headers plus the legacy proxy ones. */
const HOP_BY_HOP: Record<string, true> = {
  connection: true,
  "keep-alive": true,
  "proxy-authenticate": true,
  "proxy-authorization": true,
  "proxy-connection": true,
  te: true,
  trailer: true,
  "transfer-encoding": true,
  upgrade: true,
};

/**
 * Request headers never forwarded: hop-by-hop, ones `fetch` must compute
 * itself (Host, Content-Length for a streamed body, Expect), Accept-Encoding
 * (the response is decoded here, see RESPONSE_DROP) and the forwarding headers
 * this proxy rewrites.
 */
const REQUEST_DROP: Record<string, true> = {
  ...HOP_BY_HOP,
  host: true,
  "content-length": true,
  expect: true,
  "accept-encoding": true,
  "x-forwarded-host": true,
  "x-forwarded-proto": true,
};

/** Next.js internal request headers; they mean nothing to the API. */
const REQUEST_DROP_PREFIXES = ["x-middleware-", "x-invoke-", "x-nextjs-"];

/**
 * Response headers never copied: hop-by-hop, Set-Cookie (re-added one by one
 * so multiple cookies survive), and Content-Encoding/Content-Length because
 * `fetch` has already decoded the body.
 */
const RESPONSE_DROP: Record<string, true> = {
  ...HOP_BY_HOP,
  "set-cookie": true,
  "content-encoding": true,
  "content-length": true,
};

const BODYLESS_METHODS: Record<string, true> = { GET: true, HEAD: true };

export type ProxyConfigResult =
  | { ok: true; config: ProxyConfig }
  | { ok: false; reason: string };

/**
 * Reads the proxy configuration at request time. `API_INTERNAL_URL` accepts a
 * full origin (`http://api:8080`) or a bare `host:port`, which is what a Render
 * Blueprint `fromService … property: hostport` reference produces.
 */
export function resolveProxyConfig(env: ProxyEnv): ProxyConfigResult {
  const raw = env.API_INTERNAL_URL?.trim() ?? "";
  let upstreamText = raw;
  if (!upstreamText) {
    if (env.NODE_ENV === "production") {
      return { ok: false, reason: "API_INTERNAL_URL is not set" };
    }
    upstreamText = DEV_DEFAULT_UPSTREAM;
  }
  if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(upstreamText)) {
    upstreamText = `http://${upstreamText}`;
  }

  let url: URL;
  try {
    url = new URL(upstreamText);
  } catch {
    return { ok: false, reason: "API_INTERNAL_URL is not a valid URL" };
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    return { ok: false, reason: "API_INTERNAL_URL must use http or https" };
  }
  if (url.username || url.password || url.search || url.hash) {
    return {
      ok: false,
      reason: "API_INTERNAL_URL must not contain credentials, a query or a fragment",
    };
  }
  if (url.pathname !== "/") {
    return { ok: false, reason: "API_INTERNAL_URL must be an origin without a path" };
  }

  const maxRaw = env.API_PROXY_MAX_BODY_BYTES?.trim();
  let maxBodyBytes = DEFAULT_MAX_BODY_BYTES;
  if (maxRaw) {
    if (!/^\d+$/.test(maxRaw) || Number(maxRaw) < 1) {
      return { ok: false, reason: "API_PROXY_MAX_BODY_BYTES must be a positive integer" };
    }
    maxBodyBytes = Number(maxRaw);
  }

  return {
    ok: true,
    config: { upstream: url.origin, maxBodyBytes, timeoutMs: UPSTREAM_TIMEOUT_MS },
  };
}

function jsonError(status: number, error: string, message: string): Response {
  return new Response(JSON.stringify({ error, message, status }), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store" },
  });
}

/** Names listed in a `Connection` header are hop-by-hop for that message. */
function connectionTokens(headers: Headers): Set<string> {
  const value = headers.get("connection");
  if (!value) return new Set();
  return new Set(
    value
      .split(",")
      .map((token) => token.trim().toLowerCase())
      .filter(Boolean),
  );
}

/** Builds the upstream request headers from the browser's request. */
export function forwardRequestHeaders(request: Request): Headers {
  const incoming = request.headers;
  const listed = connectionTokens(incoming);
  const out = new Headers();
  incoming.forEach((value, name) => {
    const lower = name.toLowerCase();
    if (Object.hasOwn(REQUEST_DROP, lower) || listed.has(lower)) return;
    if (REQUEST_DROP_PREFIXES.some((prefix) => lower.startsWith(prefix))) return;
    out.set(name, value);
  });

  // Describe the browser-facing origin. A fronting load balancer's values win
  // over what this server observed directly.
  const url = new URL(request.url);
  const host = incoming.get("x-forwarded-host") ?? incoming.get("host") ?? url.host;
  const proto =
    incoming.get("x-forwarded-proto") ?? url.protocol.replace(/:$/, "");
  out.set("x-forwarded-host", host);
  out.set("x-forwarded-proto", proto);
  return out;
}

/** Copies upstream response headers, keeping every Set-Cookie separately. */
export function forwardResponseHeaders(upstream: Response): Headers {
  const listed = connectionTokens(upstream.headers);
  const out = new Headers();
  upstream.headers.forEach((value, name) => {
    const lower = name.toLowerCase();
    if (Object.hasOwn(RESPONSE_DROP, lower) || listed.has(lower)) return;
    out.append(name, value);
  });
  for (const cookie of upstream.headers.getSetCookie()) {
    out.append("set-cookie", cookie);
  }
  return out;
}

class BodyTooLargeError extends Error {
  constructor() {
    super("request body exceeds the proxy limit");
    this.name = "BodyTooLargeError";
  }
}

/** Passes the body through unchanged, erroring once it exceeds `limit` bytes. */
function capBody(
  body: ReadableStream<Uint8Array>,
  limit: number,
  onExceeded: () => void,
): ReadableStream<Uint8Array> {
  let seen = 0;
  return body.pipeThrough(
    new TransformStream<Uint8Array, Uint8Array>({
      transform(chunk, controller) {
        seen += chunk.byteLength;
        if (seen > limit) {
          onExceeded();
          controller.error(new BodyTooLargeError());
          return;
        }
        controller.enqueue(chunk);
      },
    }),
  );
}

export interface ProxyDeps {
  env: ProxyEnv;
  fetchImpl?: typeof fetch;
  /** Server-side diagnostics; never sent to the browser. */
  log?: (message: string) => void;
}

/**
 * Forwards one `/api/*` request to the configured upstream and returns the
 * upstream response (or a JSON error).
 */
export async function proxyApiRequest(
  request: Request,
  deps: ProxyDeps,
): Promise<Response> {
  const log = deps.log ?? ((message: string) => console.error(message));
  const resolved = resolveProxyConfig(deps.env);
  if (!resolved.ok) {
    log(`[api-proxy] misconfigured: ${resolved.reason}`);
    return jsonError(500, "PROXY_MISCONFIGURED", "The dashboard cannot reach the API.");
  }
  const { upstream, maxBodyBytes, timeoutMs } = resolved.config;

  const url = new URL(request.url);
  if (!url.pathname.startsWith("/api/")) {
    return jsonError(404, "NOT_FOUND", "Not found.");
  }
  // `upstream` is a bare origin and the path starts with "/api/", so the
  // concatenation can only ever address the configured host.
  const target = `${upstream}${url.pathname}${url.search}`;
  const method = request.method.toUpperCase();

  let body: ReadableStream<Uint8Array> | undefined;
  let bodyTooLarge = false;
  if (!Object.hasOwn(BODYLESS_METHODS, method) && request.body) {
    const declared = request.headers.get("content-length");
    if (declared && /^\d+$/.test(declared) && Number(declared) > maxBodyBytes) {
      return jsonError(413, "PAYLOAD_TOO_LARGE", "Request body is too large.");
    }
    body = capBody(request.body, maxBodyBytes, () => {
      bodyTooLarge = true;
    });
  }

  const timeout = AbortSignal.timeout(timeoutMs);
  const signal = AbortSignal.any([request.signal, timeout]);
  const fetchImpl = deps.fetchImpl ?? fetch;

  let upstreamResponse: Response;
  try {
    upstreamResponse = await fetchImpl(target, {
      method,
      headers: forwardRequestHeaders(request),
      body,
      redirect: "manual",
      signal,
      cache: "no-store",
      // Required by undici for a streamed request body.
      ...(body ? { duplex: "half" } : {}),
    } as RequestInit);
  } catch (error) {
    if (bodyTooLarge) {
      return jsonError(413, "PAYLOAD_TOO_LARGE", "Request body is too large.");
    }
    const name = error instanceof Error ? error.name : "Error";
    if (timeout.aborted) {
      log(`[api-proxy] ${method} ${url.pathname} timed out after ${timeoutMs} ms`);
      return jsonError(504, "UPSTREAM_TIMEOUT", "The API did not respond in time.");
    }
    log(`[api-proxy] ${method} ${url.pathname} failed: ${name}`);
    return jsonError(502, "UPSTREAM_UNAVAILABLE", "The API is unreachable. Try again shortly.");
  }

  const nullBody =
    method === "HEAD" ||
    upstreamResponse.status === 204 ||
    upstreamResponse.status === 304;
  return new Response(nullBody ? null : upstreamResponse.body, {
    status: upstreamResponse.status,
    statusText: upstreamResponse.statusText,
    headers: forwardResponseHeaders(upstreamResponse),
  });
}
