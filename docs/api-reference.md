# CodeLens — API Reference

This document covers every HTTP endpoint in the system.

- **Spring Boot REST API** (`apps/api`, port `8080`) — 12 endpoints, public.
- **FastAPI ML worker** (`apps/ml-worker`, port `8000`) — 2 endpoints, internal-only.

Source: [ENGINEERING_PLAN.md](../ENGINEERING_PLAN.md) Section 4.

---

## 1. Authentication

All protected endpoints require one of:

- `Authorization: Bearer <jwt_token>` — issued by `/api/auth/callback` for
  Next.js dashboard users. 15-minute TTL, refresh via `/api/auth/refresh`.
- `Authorization: Bearer cl_live_xxxxx` — VS Code extension API key.

Standard error envelope (all endpoints):

```json
{
  "error":      "REPO_NOT_FOUND",
  "message":    "Repository with id abc123 not found",
  "timestamp":  "2026-06-19T10:30:00Z"
}
```

---

## 2. Spring Boot Endpoints

### 2.1. `GET /api/auth/github`

Starts the GitHub OAuth flow.

| Property | Value |
|---|---|
| Method | `GET` |
| Path | `/api/auth/github` |
| Auth | None (browser redirect) |
| Request body | — |
| Response 302 | Redirect to `https://github.com/login/oauth/authorize?...` |
| Response 4xx | None expected (errors are server-side) |

---

### 2.2. `GET /api/auth/callback`

OAuth callback — GitHub redirects the browser here with `?code=xxx`.

| Property | Value |
|---|---|
| Method | `GET` |
| Path | `/api/auth/callback?code={github_code}` |
| Auth | None (validated by GitHub) |
| Request body | — |
| Response 200 | `{ "accessToken": "eyJ...", "expiresIn": 3600, "user": { "id": "uuid", "githubUsername": "...", "avatarUrl": "..." } }` |
| Side effects | Creates/updates `users` row; sets httpOnly cookies for access + refresh tokens |
| Response 4xx | `INVALID_OAUTH_CODE` → 400 |

---

### 2.3. `POST /api/auth/refresh`

Exchanges a valid refresh token for a new short-lived access token.

| Property | Value |
|---|---|
| Method | `POST` |
| Path | `/api/auth/refresh` |
| Auth | None (refresh token in body) |
| Request body | `{ "refreshToken": "eyJ..." }` |
| Response 200 | `{ "accessToken": "eyJ...", "expiresIn": 3600 }` |
| Response 401 | `INVALID_REFRESH_TOKEN` |

---

### 2.4. `GET /api/auth/me`

Returns the current user's profile, including the VS Code API key.

| Property | Value |
|---|---|
| Method | `GET` |
| Path | `/api/auth/me` |
| Auth | JWT required |
| Request body | — |
| Response 200 | `{ "id": "uuid", "githubUsername": "tanmay-alpha", "avatarUrl": "https://...", "apiKey": "cl_live_xxx…", "createdAt": "2026-01-01T00:00:00Z" }` |
| Response 401 | `UNAUTHENTICATED` |

---

### 2.5. `POST /api/auth/api-key/regenerate`

Issues a fresh `cl_live_*` API key, invalidating the previous one.

| Property | Value |
|---|---|
| Method | `POST` |
| Path | `/api/auth/api-key/regenerate` |
| Auth | JWT required |
| Request body | — |
| Response 200 | `{ "apiKey": "cl_live_newkeyabc...", "prefix": "cl_live_new" }` |
| Response 401 | `UNAUTHENTICATED` |

---

### 2.6. `GET /api/repos`

Lists all repos connected by the authenticated user.

| Property | Value |
|---|---|
| Method | `GET` |
| Path | `/api/repos` |
| Auth | JWT required |
| Request body | — |
| Response 200 | `[ { "id": "uuid", "fullName": "tanmay-alpha/MAET", "description": "...", "isPrivate": false, "webhookActive": true, "qualityScore": 74.2, "lastReviewedAt": "2026-06-18T09:00:00Z" } ]` |
| Response 401 | `UNAUTHENTICATED` |

---

### 2.7. `POST /api/repos/connect`

Connects a GitHub repo: installs a CodeLens webhook and creates a `repositories` row.

| Property | Value |
|---|---|
| Method | `POST` |
| Path | `/api/repos/connect` |
| Auth | JWT required |
| Request body | `{ "githubRepoFullName": "tanmay-alpha/MAET" }` |
| Response 201 | `{ "id": "uuid", "fullName": "tanmay-alpha/MAET", "webhookId": 123456789, "webhookActive": true }` |
| Response 401 | `UNAUTHENTICATED` |
| Response 403 | `GITHUB_FORBIDDEN` (no admin rights on target repo) |
| Response 409 | `REPO_ALREADY_CONNECTED` |
| Response 404 | `GITHUB_REPO_NOT_FOUND` |

---

### 2.8. `DELETE /api/repos/{repoId}`

Removes the GitHub webhook and marks the repo inactive (soft delete).

| Property | Value |
|---|---|
| Method | `DELETE` |
| Path | `/api/repos/{repoId}` |
| Auth | JWT required |
| Request body | — |
| Response 204 | (empty) |
| Response 401 | `UNAUTHENTICATED` |
| Response 404 | `REPO_NOT_FOUND` |

---

### 2.9. `GET /api/repos/{repoId}/prs`

Paginated list of pull requests for a repo, newest first.

| Property | Value |
|---|---|
| Method | `GET` |
| Path | `/api/repos/{repoId}/prs?page=1&pageSize=20` |
| Auth | JWT required |
| Request body | — |
| Response 200 | `{ "data": [ { "id": "uuid", "prNumber": 42, "title": "Add user auth module", "author": "tanmay-alpha", "status": "reviewed", "qualityScore": 68.5, "findingsCount": { "critical": 1, "major": 2, "minor": 3 }, "reviewedAt": "2026-06-18T09:00:00Z" } ], "total": 47, "page": 1, "pageSize": 20 }` |
| Response 401 | `UNAUTHENTICATED` |
| Response 404 | `REPO_NOT_FOUND` |

---

### 2.10. `GET /api/reviews/{prId}`

Full review for a single PR — diff metadata + every finding.

| Property | Value |
|---|---|
| Method | `GET` |
| Path | `/api/reviews/{prId}` |
| Auth | JWT required |
| Request body | — |
| Response 200 | `{ "id": "uuid", "prNumber": 42, "title": "Add user auth module", "qualityScore": 68.5, "githubPrUrl": "https://github.com/tanmay-alpha/MAET/pull/42", "findings": [ { "id": "uuid", "filePath": "src/auth/service.py", "lineStart": 23, "lineEnd": 31, "antiPattern": "PERFORMANCE_N_PLUS_1", "category": "PERFORMANCE", "severity": "major", "confidence": 0.91, "explanation": "Database query inside a loop detected. Move query outside the loop or use bulk fetch." } ], "reviewedAt": "2026-06-18T09:00:00Z" }` |
| Response 401 | `UNAUTHENTICATED` |
| Response 404 | `PR_NOT_FOUND` |

---

### 2.11. `POST /api/scan/file`

Single-file scan — called by the VS Code extension on save.

| Property | Value |
|---|---|
| Method | `POST` |
| Path | `/api/scan/file` |
| Auth | **API key** (`Authorization: Bearer cl_live_xxx`) |
| Request body | `{ "content": "def get_users():\n    for u in users:\n        db.query(...)", "language": "python", "filePath": "src/service.py" }` |
| Response 200 | `{ "findings": [ { "lineStart": 3, "lineEnd": 4, "antiPattern": "PERFORMANCE_N_PLUS_1", "category": "PERFORMANCE", "severity": "major", "confidence": 0.88, "explanation": "Database query inside a loop." } ], "qualityScore": 72.0 }` |
| Response 401 | `INVALID_API_KEY` |
| Response 422 | `INVALID_REQUEST` (empty content, missing language) |
| Response 429 | `RATE_LIMITED` (60 req/min per API key) |

---

### 2.12. `POST /api/scan/action`

Full PR scan + post comment to GitHub — called by the GitHub Action.

| Property | Value |
|---|---|
| Method | `POST` |
| Path | `/api/scan/action` |
| Auth | **GITHUB_TOKEN** (validated against the calling workflow's installation) |
| Request body | `{ "diff": "unified diff string", "repoFullName": "tanmay-alpha/MAET", "prNumber": 42, "language": "python" }` |
| Response 200 | `{ "findings": [ ... ], "qualityScore": 68.5, "postedToGitHub": true }` |
| Response 401 | `INVALID_GITHUB_TOKEN` |
| Response 422 | `INVALID_REQUEST` |
| Response 502 | `ML_WORKER_UNAVAILABLE` |

---

## 3. FastAPI Endpoints (Internal)

The ML worker is **not** exposed to the public internet in production. Spring Boot
authenticates by sending `X-Internal-Secret: $ML_WORKER_SECRET`.

### 3.1. `POST /ml/review`

Runs inference over a diff or whole file and returns findings.

| Property | Value |
|---|---|
| Method | `POST` |
| Path | `/ml/review` |
| Auth | `X-Internal-Secret: $ML_WORKER_SECRET` header (Spring Boot only) |
| Request body | `{ "diff": "unified diff or file content string", "language": "python", "mode": "diff" }` (`mode` ∈ `diff` \| `file`) |
| Response 200 | `{ "findings": [ { "lineStart": 23, "lineEnd": 31, "antiPattern": "PERFORMANCE_N_PLUS_1", "category": "PERFORMANCE", "severity": "major", "confidence": 0.91, "explanation": "Database query inside a loop." } ], "qualityScore": 68.5, "processingTimeMs": 187, "windowsProcessed": 1 }` |
| Response 401 | `INVALID_INTERNAL_SECRET` |
| Response 422 | `{ "detail": "diff must not be empty" }` (Pydantic validation) |
| Response 503 | `MODEL_NOT_LOADED` |

---

### 3.2. `GET /ml/health`

Liveness/readiness probe. Returns model load status.

| Property | Value |
|---|---|
| Method | `GET` |
| Path | `/ml/health` |
| Auth | None (internal network only) |
| Request body | — |
| Response 200 | `{ "status": "ok", "modelLoaded": true, "modelName": "tanmay-alpha/codelens-codebert", "device": "cpu" }` |
| Response 503 | `{ "status": "loading", "modelLoaded": false, ... }` (first ~30s after container start) |

---

## 4. Webhook Endpoint

GitHub calls this directly — it sits inside `/api/webhook/github` and is part of
the Spring Boot surface, but uses HMAC verification instead of user auth.

### `POST /api/webhook/github`

| Property | Value |
|---|---|
| Method | `POST` |
| Path | `/api/webhook/github` |
| Auth | **HMAC-SHA256** via `X-Hub-Signature-256` header |
| Required headers | `X-Hub-Signature-256`, `X-GitHub-Event`, `X-GitHub-Delivery` |
| Request body (from GitHub) | `{ "action": "opened", "pull_request": { "number": 42, "title": "...", "user": { "login": "tanmay-alpha" }, "head": { "sha": "abc123" }, "diff_url": "https://github.com/.../42.diff" }, "repository": { "id": 789, "full_name": "tanmay-alpha/MAET" } }` |
| Response 200 | `{ "status": "accepted" }` (always 200 — processing is `@Async`) |
| Response 401 | `INVALID_SIGNATURE` (HMAC mismatch) |
| Response 200 (duplicate) | `{ "status": "duplicate" }` (X-GitHub-Delivery already in `processed_webhooks`) |
| Response 400 | `UNSUPPORTED_EVENT` (event type ≠ `pull_request`) |

---

## 5. Error Codes

Every error response uses the standard envelope from Section 1. The `error`
field is a stable machine-readable code. `message` is human-readable and may
change wording without notice.

| Error code | HTTP status | Meaning |
|---|---|---|
| `UNAUTHENTICATED` | 401 | Missing or expired JWT |
| `INVALID_API_KEY` | 401 | `cl_live_*` key not found, revoked, or wrong format |
| `INVALID_REFRESH_TOKEN` | 401 | Refresh token unknown / expired / revoked |
| `INVALID_OAUTH_CODE` | 400 | GitHub rejected the OAuth `code` parameter |
| `INVALID_GITHUB_TOKEN` | 401 | GitHub Action token failed installation validation |
| `INVALID_SIGNATURE` | 401 | Webhook HMAC-SHA256 verification failed (forged or wrong secret) |
| `INVALID_INTERNAL_SECRET` | 401 | FastAPI request lacked the `X-Internal-Secret` header |
| `INVALID_REQUEST` | 422 | Request body validation failed (Pydantic / Spring `@Valid`) |
| `UNSUPPORTED_EVENT` | 400 | Webhook `X-GitHub-Event` is not `pull_request` |
| `RATE_LIMITED` | 429 | Per-IP or per-API-key quota exceeded (60/min file scan, 10/min auth) |
| `REPO_NOT_FOUND` | 404 | `repoId` does not exist or does not belong to caller |
| `GITHUB_REPO_NOT_FOUND` | 404 | Target repo does not exist or caller cannot see it |
| `PR_NOT_FOUND` | 404 | `prId` does not exist or is not visible to caller |
| `GITHUB_FORBIDDEN` | 403 | Caller lacks admin rights to install webhook on the repo |
| `REPO_ALREADY_CONNECTED` | 409 | Repo is already linked to a CodeLens user |
| `ML_WORKER_UNAVAILABLE` | 502 | FastAPI did not respond in time or returned 5xx |
| `MODEL_NOT_LOADED` | 503 | FastAPI container still loading the model from HuggingFace |
| `INTERNAL_ERROR` | 500 | Unhandled server error — details logged with a correlation id |