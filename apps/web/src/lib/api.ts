// =============================================================================
// Browser-side API client.
//
// Every request goes to a same-origin relative URL (`/api/...`). The Next.js
// route handler in `app/api/[...path]/route.ts` forwards it to the Spring API
// (`API_INTERNAL_URL`), so the httpOnly auth cookies the API sets belong to
// the dashboard's own origin and are sent on every request. Tokens are never
// stored in JS-accessible storage.
// =============================================================================

import type {
  ApiKeyResponse,
  CreatedApiKeyResponse,
  DiagnosticsResponse,
  FindingAction,
  FindingActionRequest,
  FindingActionResponse,
  FindingSummaryResponse,
  PaginatedPRResponse,
  PullRequestSummary,
  QualityTrendResponse,
  RepoResponse,
  RepoSettings,
  ReviewDetailResponse,
  ReviewRun,
  UserResponse,
} from "@/lib/types";

/** Starts the GitHub OAuth flow (the API answers with a 302 to GitHub). */
export const LOGIN_PATH = "/api/auth/github";

export class ApiError extends Error {
  readonly status: number;
  readonly url: string;
  /** Machine-readable `error` field of the API's JSON error body, if any. */
  readonly code: string | null;
  constructor(message: string, status: number, url: string, code: string | null = null) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.url = url;
    this.code = code;
  }
}

interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "DELETE";
  body?: unknown;
}

/**
 * Error code of the 401 that POST /api/auth/refresh answers when GitHub
 * reports the stored grant revoked or expired: the session is gone and only a
 * new GitHub sign-in restores it.
 */
export const GITHUB_REAUTH_REQUIRED = "GITHUB_REAUTH_REQUIRED";

/** `ok`: new cookies issued; `reauth`: GITHUB_REAUTH_REQUIRED; `failed`: anything else. */
type RefreshResult = "ok" | "reauth" | "failed";

let refreshInFlight: Promise<RefreshResult> | null = null;

/**
 * POST /api/auth/refresh — trades the refresh cookie for a new access cookie
 * (the access token lives 15 minutes). The API rotates the refresh token and
 * rejects reuse of the old one, so concurrent 401s must share one attempt.
 */
function refreshSession(): Promise<RefreshResult> {
  refreshInFlight ??= fetch("/api/auth/refresh", {
    method: "POST",
    credentials: "same-origin",
  })
    .then(async (res): Promise<RefreshResult> => {
      if (res.ok) return "ok";
      const err = await toApiError(res, "POST", "/api/auth/refresh");
      return err.code === GITHUB_REAUTH_REQUIRED ? "reauth" : "failed";
    })
    .catch((): RefreshResult => "failed")
    .finally(() => {
      refreshInFlight = null;
    });
  return refreshInFlight;
}

/**
 * Builds an ApiError from a failed response. The API (and the proxy) answer
 * with `{error, message, status}`; fall back to a generic message rather than
 * showing raw bodies such as HTML error pages.
 */
async function toApiError(res: Response, method: string, path: string): Promise<ApiError> {
  const fallback = `${method} ${path} failed with HTTP ${res.status}`;
  const text = await res.text().catch(() => "");
  let code: string | null = null;
  let message = fallback;
  if (text) {
    try {
      const parsed: unknown = JSON.parse(text);
      if (parsed && typeof parsed === "object") {
        if ("error" in parsed && typeof parsed.error === "string") code = parsed.error;
        if ("message" in parsed && typeof parsed.message === "string" && parsed.message.trim()) {
          message = parsed.message.trim();
        }
      }
    } catch {
      // Not JSON; keep the generic message.
    }
  }
  return new ApiError(message, res.status, path, code);
}

async function request<T>(
  path: string,
  opts: RequestOptions = {},
  isRetry = false,
): Promise<T> {
  const { method = "GET", body } = opts;

  const res = await fetch(path, {
    method,
    credentials: "same-origin",
    headers: {
      Accept: "application/json",
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
    },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  });

  if (res.status === 401 && !isRetry) {
    const refreshed = await refreshSession();
    if (refreshed === "ok") {
      return request<T>(path, opts, true);
    }
    if (refreshed === "reauth") {
      throw new ApiError(
        "GitHub authorization was revoked or expired; sign in again.",
        401,
        path,
        GITHUB_REAUTH_REQUIRED,
      );
    }
  }

  if (!res.ok) {
    throw await toApiError(res, method, path);
  }

  if (res.status === 204) {
    return undefined as T;
  }
  return (await res.json()) as T;
}

const enc = encodeURIComponent;

// =============================================================================
// /api/auth
// =============================================================================

/** GET /api/auth/me → UserResponse (401 if not signed in). */
export function getMe(): Promise<UserResponse> {
  return request<UserResponse>("/api/auth/me");
}

/** POST /api/auth/logout-all → 204. Revokes every session on every device. */
export async function logoutAll(): Promise<void> {
  await request<void>("/api/auth/logout-all", { method: "POST" });
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
  await request<void>(`/api/repos/${enc(repoId)}`, { method: "DELETE" });
}

/** GET /api/repos/{id} → RepoResponse. */
export function getRepoDetail(repoId: string): Promise<RepoResponse> {
  return request<RepoResponse>(`/api/repos/${enc(repoId)}`);
}

/** GET /api/repos/{id}/prs?page=N&size=M → one page of pull requests. */
export function getRepoPRs(
  repoId: string,
  page: number,
  size = 20,
): Promise<PaginatedPRResponse<PullRequestSummary>> {
  return request<PaginatedPRResponse<PullRequestSummary>>(
    `/api/repos/${enc(repoId)}/prs?page=${page}&size=${size}`,
  );
}

/** GET /api/repos/{id}/settings → RepoSettings (404 for a repository you do not own). */
export function getRepoSettings(repoId: string): Promise<RepoSettings> {
  return request<RepoSettings>(`/api/repos/${enc(repoId)}/settings`);
}

/** PUT /api/repos/{id}/settings → the saved RepoSettings (400 VALIDATION_FAILED on bad input). */
export function updateRepoSettings(
  repoId: string,
  settings: RepoSettings,
): Promise<RepoSettings> {
  return request<RepoSettings>(`/api/repos/${enc(repoId)}/settings`, {
    method: "PUT",
    body: settings,
  });
}

// =============================================================================
// /api/reviews
// =============================================================================

/** GET /api/reviews/{prId} → ReviewDetailResponse. */
export function getPRReview(prId: string): Promise<ReviewDetailResponse> {
  return request<ReviewDetailResponse>(`/api/reviews/${enc(prId)}`);
}

/** GET /api/reviews/{prId}/runs → newest-first webhook deliveries (max 50). */
export function getReviewRuns(prId: string): Promise<ReviewRun[]> {
  return request<ReviewRun[]>(`/api/reviews/${enc(prId)}/runs`);
}

// =============================================================================
// /api/scan
// =============================================================================

/**
 * POST /api/scan/action → FindingActionResponse. Records a triage action on a
 * finding. `reason` is required by the API for `dismiss` and `false_positive`.
 * 404 when the finding does not exist or belongs to a repository the caller
 * does not own.
 */
export function recordFindingAction(
  findingId: string,
  action: FindingAction,
  reason?: string,
): Promise<FindingActionResponse> {
  const body: FindingActionRequest = { findingId, action };
  const trimmed = reason?.trim();
  if (trimmed) body.reason = trimmed;
  return request<FindingActionResponse>("/api/scan/action", { method: "POST", body });
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
    `/api/metrics/quality-trend?repo=${enc(repoId)}&days=${days}`,
  );
}

/** GET /api/metrics/finding-summary?repo=<uuid>&days=<n> → per-rule triage counts. */
export function getFindingSummary(
  repoId: string,
  days = 30,
): Promise<FindingSummaryResponse> {
  return request<FindingSummaryResponse>(
    `/api/metrics/finding-summary?repo=${enc(repoId)}&days=${days}`,
  );
}

// =============================================================================
// /api/diagnostics
// =============================================================================

/** GET /api/diagnostics → component health and the caller's queue counts. */
export function getDiagnostics(): Promise<DiagnosticsResponse> {
  return request<DiagnosticsResponse>("/api/diagnostics");
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
  await request<void>(`/api/auth/api-keys/${enc(id)}`, { method: "DELETE" });
}
