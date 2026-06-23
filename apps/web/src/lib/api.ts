// =============================================================================
// Browser-side API client.
//
// All requests carry `credentials: "include"` so the browser forwards the
// httpOnly `accessToken` / `refreshToken` cookies set by Spring Boot's
// `AuthController`. We never store tokens in JS-accessible storage —
// that would defeat the XSS-protection of httpOnly.
//
// The base URL defaults to `http://localhost:8080` and can be overridden
// with NEXT_PUBLIC_API_BASE_URL in `.env.local`.
// =============================================================================

import type {
  ApiKeyResponse,
  CreatedApiKeyResponse,
  PaginatedPRResponse,
  PullRequestSummary,
  QualityTrendResponse,
  RegenerateApiKeyResponse,
  RepoDetailResponse,
  RepoSummaryResponse,
  ReviewDetailResponse,
  UserResponse,
} from "@/lib/types";

const BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL?.replace(/\/+$/, "") ??
  "http://localhost:8080";

export class ApiError extends Error {
  readonly status: number;
  readonly url: string;
  constructor(message: string, status: number, url: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.url = url;
  }
}

interface RequestOptions {
  method?: "GET" | "POST" | "DELETE" | "PUT";
  body?: unknown;
  /** Override the default content-type. */
  contentType?: string;
  /** Extra headers to merge in. */
  headers?: Record<string, string>;
}

async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const { method = "GET", body, contentType, headers = {} } = opts;
  const url = `${BASE_URL}${path}`;

  const init: RequestInit = {
    method,
    credentials: "include",
    headers: {
      Accept: "application/json",
      ...(body !== undefined ? { "Content-Type": contentType ?? "application/json" } : {}),
      ...headers,
    },
    ...(body !== undefined
      ? { body: typeof body === "string" ? body : JSON.stringify(body) }
      : {}),
  };

  const res = await fetch(url, init);

  if (!res.ok) {
    // 204 No Content — return as `undefined as T` for caller convenience.
    if (res.status === 204) {
      return undefined as T;
    }
    const text = await res.text().catch(() => "");
    throw new ApiError(
      text || `${method} ${path} failed with ${res.status}`,
      res.status,
      url,
    );
  }

  // 204 may also slip through here for callers that don't expect void.
  if (res.status === 204) {
    return undefined as T;
  }
  // Some endpoints (like /api/auth/api-key/regenerate) return a JSON object
  // — `await res.json()` is what we want.
  return (await res.json()) as T;
}

// =============================================================================
// /api/auth
// =============================================================================

/** GET /api/auth/me → UserResponse (401 if not signed in). */
export function getMe(): Promise<UserResponse> {
  return request<UserResponse>("/api/auth/me");
}

/** POST /api/auth/logout → 204. */
export async function logout(): Promise<void> {
  await request<void>("/api/auth/logout", { method: "POST" });
}

// =============================================================================
// /api/repos
// =============================================================================

/** GET /api/repos → RepoSummaryResponse[]. */
export function getRepos(): Promise<RepoSummaryResponse[]> {
  return request<RepoSummaryResponse[]>("/api/repos");
}

/** POST /api/repos/connect → RepoResponse (sets webhook, returns secret in header). */
export function connectRepo(fullName: string): Promise<RepoSummaryResponse> {
  return request<RepoSummaryResponse>("/api/repos/connect", {
    method: "POST",
    body: { fullName },
  });
}

/** DELETE /api/repos/{id} → 204. */
export async function disconnectRepo(repoId: string): Promise<void> {
  await request<void>(`/api/repos/${encodeURIComponent(repoId)}`, {
    method: "DELETE",
  });
}

/** GET /api/repos/{id} → RepoDetailResponse. */
export function getRepoDetail(repoId: string): Promise<RepoDetailResponse> {
  return request<RepoDetailResponse>(`/api/repos/${encodeURIComponent(repoId)}`);
}

/**
 * GET /api/repos/{id}/prs?page=N&size=20 → PaginatedPRResponse<PullRequestSummary>.
 *
 * The Spring endpoint returns `Page<PullRequestEntity>` which serializes
 * with a `content: [...]` array; we map each entity to a slim summary
 * the dashboard can render.
 */
export async function getRepoPRs(
  repoId: string,
  page: number,
  size = 20,
): Promise<PaginatedPRResponse<PullRequestSummary>> {
  const raw = await request<
    PaginatedPRResponse<{
      id: string;
      githubPrNumber: number;
      title: string | null;
      authorGithub: string | null;
      headSha: string | null;
      githubPrUrl: string | null;
      status: string;
      qualityScore: number | null;
      githubCommentId: number | null;
      errorMessage: string | null;
      reviewedAt: string | null;
      createdAt: string;
    }>
  >(
    `/api/repos/${encodeURIComponent(repoId)}/prs?page=${page}&size=${size}`,
  );
  // Normalize: strip the `repo` nested object (we know it from the URL).
  return {
    ...raw,
    content: raw.content.map((p) => ({
      id: p.id,
      githubPrNumber: p.githubPrNumber,
      title: p.title,
      authorGithub: p.authorGithub,
      headSha: p.headSha,
      githubPrUrl: p.githubPrUrl,
      status: p.status,
      qualityScore: p.qualityScore,
      githubCommentId: p.githubCommentId,
      errorMessage: p.errorMessage,
      reviewedAt: p.reviewedAt,
      createdAt: p.createdAt,
    })),
  };
}

// =============================================================================
// /api/reviews
// =============================================================================

/** GET /api/reviews/{prId} → ReviewDetailResponse. */
export function getPRReview(prId: string): Promise<ReviewDetailResponse> {
  return request<ReviewDetailResponse>(`/api/reviews/${encodeURIComponent(prId)}`);
}

// =============================================================================
// /api/metrics
// =============================================================================

/**
 * GET /api/metrics/quality-trend?repo=<uuid>&days=<n> → QualityTrendResponse.
 *
 * NB: the Spring endpoint takes `repo` as a query param, NOT a path segment.
 */
export function getQualityTrend(
  repoId: string,
  days = 30,
): Promise<QualityTrendResponse> {
  return request<QualityTrendResponse>(
    `/api/metrics/quality-trend?repo=${encodeURIComponent(repoId)}&days=${days}`,
  );
}

// =============================================================================
// /api/auth/api-keys
// =============================================================================

/** POST /api/auth/api-key/regenerate → { apiKey: string }. */
export function regenerateApiKey(): Promise<RegenerateApiKeyResponse> {
  return request<RegenerateApiKeyResponse>("/api/auth/api-key/regenerate", {
    method: "POST",
  });
}

/** GET /api/auth/api-keys → ApiKeyResponse[]. */
export function listApiKeys(): Promise<ApiKeyResponse[]> {
  return request<ApiKeyResponse[]>("/api/auth/api-keys");
}

/** POST /api/auth/api-keys → CreatedApiKeyResponse (plaintext returned ONCE). */
export function createApiKey(label: string): Promise<CreatedApiKeyResponse> {
  return request<CreatedApiKeyResponse>("/api/auth/api-keys", {
    method: "POST",
    body: { label },
  });
}

/** DELETE /api/auth/api-keys/{id} → 204. */
export async function revokeApiKey(id: string): Promise<void> {
  await request<void>(`/api/auth/api-keys/${encodeURIComponent(id)}`, {
    method: "DELETE",
  });
}

// Re-export so consumers can use one import.
export { BASE_URL };
