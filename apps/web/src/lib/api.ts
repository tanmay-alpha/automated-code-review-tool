// =============================================================================
// Browser-side API client.
//
// All requests carry `credentials: "include"` so the browser forwards the
// httpOnly `accessToken` / `refreshToken` cookies set by Spring Boot's
// `AuthController`. Tokens are never stored in JS-accessible storage.
//
// The base URL defaults to `http://localhost:8080` and can be overridden
// with NEXT_PUBLIC_API_BASE_URL in `.env.local`.
// =============================================================================

import type {
  ApiKeyResponse,
  CreatedApiKeyResponse,
  FindingAction,
  FindingActionResponse,
  PaginatedPRResponse,
  PullRequestSummary,
  QualityTrendResponse,
  RepoResponse,
  ReviewDetailResponse,
  UserResponse,
} from "@/lib/types";

export const BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL?.replace(/\/+$/, "") ||
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
  method?: "GET" | "POST" | "DELETE";
  body?: unknown;
}

let refreshInFlight: Promise<boolean> | null = null;

/**
 * POST /api/auth/refresh — trades the refresh cookie for a new access cookie
 * (the access token lives 15 minutes). The API rotates the refresh token and
 * rejects reuse of the old one, so concurrent 401s must share one attempt.
 */
function refreshSession(): Promise<boolean> {
  refreshInFlight ??= fetch(`${BASE_URL}/api/auth/refresh`, {
    method: "POST",
    credentials: "include",
  })
    .then((res) => res.ok)
    .catch(() => false)
    .finally(() => {
      refreshInFlight = null;
    });
  return refreshInFlight;
}

async function request<T>(
  path: string,
  opts: RequestOptions = {},
  isRetry = false,
): Promise<T> {
  const { method = "GET", body } = opts;
  const url = `${BASE_URL}${path}`;

  const res = await fetch(url, {
    method,
    credentials: "include",
    headers: {
      Accept: "application/json",
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
    },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  });

  if (res.status === 401 && !isRetry && (await refreshSession())) {
    return request<T>(path, opts, true);
  }

  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new ApiError(
      text || `${method} ${path} failed with ${res.status}`,
      res.status,
      url,
    );
  }

  if (res.status === 204) {
    return undefined as T;
  }
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

/** GET /api/repos → RepoResponse[]. */
export function getRepos(): Promise<RepoResponse[]> {
  return request<RepoResponse[]>("/api/repos");
}

/** POST /api/repos/connect → 201 RepoResponse (the API also installs the webhook). */
export function connectRepo(fullName: string): Promise<RepoResponse> {
  return request<RepoResponse>("/api/repos/connect", {
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

/** GET /api/repos/{id} → RepoResponse. */
export function getRepoDetail(repoId: string): Promise<RepoResponse> {
  return request<RepoResponse>(`/api/repos/${encodeURIComponent(repoId)}`);
}

/** GET /api/repos/{id}/prs?page=N&size=M → one page of pull requests. */
export function getRepoPRs(
  repoId: string,
  page: number,
  size = 20,
): Promise<PaginatedPRResponse<PullRequestSummary>> {
  return request<PaginatedPRResponse<PullRequestSummary>>(
    `/api/repos/${encodeURIComponent(repoId)}/prs?page=${page}&size=${size}`,
  );
}

// =============================================================================
// /api/reviews
// =============================================================================

/** GET /api/reviews/{prId} → ReviewDetailResponse. */
export function getPRReview(prId: string): Promise<ReviewDetailResponse> {
  return request<ReviewDetailResponse>(`/api/reviews/${encodeURIComponent(prId)}`);
}

// =============================================================================
// /api/scan
// =============================================================================

/**
 * POST /api/scan/action → FindingActionResponse. Records a developer
 * disposition on a finding. JWT only; 404 when the finding does not exist
 * or belongs to a repository the caller does not own.
 */
export function recordFindingAction(
  findingId: string,
  action: FindingAction,
): Promise<FindingActionResponse> {
  return request<FindingActionResponse>("/api/scan/action", {
    method: "POST",
    body: { findingId, action },
  });
}

// =============================================================================
// /api/metrics
// =============================================================================

/**
 * GET /api/metrics/quality-trend?repo=<uuid>&days=<n> → QualityTrendResponse.
 * The Spring endpoint takes `repo` as a query param, not a path segment.
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

/** GET /api/auth/api-keys → ApiKeyResponse[]. */
export function listApiKeys(): Promise<ApiKeyResponse[]> {
  return request<ApiKeyResponse[]>("/api/auth/api-keys");
}

/** POST /api/auth/api-keys → 201 CreatedApiKeyResponse (plaintext returned once). */
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
