# CodeLens — Full Root-Cause Remediation Prompt
# Feed this entire file to Claude Code (or your engineer) as the task prompt.
# It is structured to fix every audit finding from the root cause up.

---

## PROJECT CONTEXT

CodeLens is a multi-service automated code-review platform:
- **API** — Spring Boot 3.3.4 (Java 17) at `apps/api/`
- **ML Worker** — FastAPI (Python) at `apps/ml-worker/`
- **Web** — Next.js 15 (TypeScript) at `apps/web/`
- **VS Code Extension** — TypeScript at `apps/vscode-ext/`
- **Infra** — Docker Compose at `infra/`
- **CI** — GitHub Actions at `.github/workflows/`
- **GitHub Action** — Reusable action at `github-action/`

---

## GLOBAL RULES

1. **Fix the root cause, never paper over symptoms.** If a guard is wrong, correct the guard's logic — don't add a second guard next to it.
2. **Preserve existing behavior for correct inputs.** Every fix must not break the happy path.
3. **Match the existing code style** in each service (Java conventions, Python PEP 8, TypeScript/React patterns already in use).
4. **Do NOT create or modify `.env` files.** The `.env` file contains real secrets. Treat it as radioactive. Only `.env.example` may be touched (to replace real values with placeholders).
5. **Do NOT modify `.git` history.** Rotating secrets means generating new values; do not rewrite git commits.
6. **After every change, verify the change compiles / passes lint / is syntactically valid.**
7. **Work service by service.** Complete one service fully before moving to the next.

---

## SERVICE 1: API (Spring Boot) — `apps/api/`

### TASK 1.1 — Rotate Secrets Exposure (CRITICAL)

**Root cause:** Real secrets were committed to `.env` and `.env.example`.
**Actions:**
1. Read `.env.example`. Replace every `KEY=VALUE` pair where VALUE is a real secret
   with a clear placeholder (e.g., `JWT_SECRET=generate_via_openssl_rand_base64_64`).
   Keep the comments and structure intact.
2. Do NOT touch `.env` itself — it is gitignored and the user will rotate it manually.
3. Do NOT touch `infra/.env`.

### TASK 1.2 — Fix SecurityMonitor IP Clear Bug (CRITICAL)

**File:** `.../monitoring/SecurityMonitor.java`, line ~73
**Root cause:** `failedAttemptsByIP.clear()` clears ALL IPs. Should only remove the authenticating IP.
**Fix:** Replace `failedAttemptsByIP.clear()` with `failedAttemptsByIP.remove(ip)`.
This is atomic on `ConcurrentHashMap` — no additional synchronization needed.

### TASK 1.3 — Add OAuth `state` Parameter (CRITICAL)

**File:** `.../controller/AuthController.java` (OAuth methods)
**Root cause:** No CSRF protection in the OAuth handshake.
**Fix:**
1. In `startOAuth()`: generate a random `state` token (use `SecureRandom`),
   store it in a short-lived HTTP-only cookie (or session), and append `&state={token}`
   to the GitHub authorize URL.
2. In `oauthCallback()`: read the `state` from the query param and compare it to the
   cookie/session value. Reject with 400 if they don't match.
3. Delete the state cookie after successful callback.

### TASK 1.4 — Add Ownership Check to ReviewController (CRITICAL)

**File:** `.../controller/ReviewController.java`, `getReview()` method
**Root cause:** No authorization — any authenticated user can read any PR's findings.
**Fix:**
1. Inject the authenticated user's ID from the security context.
2. Look up the PR by `prId`. Then look up the PR's repository.
3. Check that `repository.getOwner().getId()` equals the authenticated user's ID.
4. If not, return 404 (not 403 — to avoid leaking that the PR exists).
5. Follow the same pattern used by `ScanController.recordAction()`.

### TASK 1.5 — Fix Resilience4j Starter Version (CRITICAL)

**File:** `apps/api/pom.xml`
**Root cause:** `resilience4j-spring-boot2` is incompatible with Spring Boot 3 (Jakarta namespace).
**Fix:** Change both resilience4j artifactIds from `resilience4j-spring-boot2` to `resilience4j-spring-boot3`.
Keep version `2.1.0`. Verify no other Boot-2-specific auto-config imports exist.

### TASK 1.6 — Fix COOKIE_SECURE in Docker Compose (CRITICAL)

**File:** `infra/docker-compose.yml`
**Root cause:** Base compose hardcodes `COOKIE_SECURE=false`, meaning anyone deploying
with the base file (not the dev overlay) sends auth cookies over plain HTTP.
**Fix:** Remove the `COOKIE_SECURE=false` line from the base `docker-compose.yml`.
The `.env` file or environment-specific overrides should set this.
Add a comment: `# Set COOKIE_SECURE=true in production (requires HTTPS).`

### TASK 1.7 — Fix GlobalExceptionHandler Error Code (HIGH)

**File:** `.../exception/GlobalExceptionHandler.java`, lines ~97-99
**Root cause:** Error code string is hardcoded to `"UNAUTHORIZED"` regardless of the actual HTTP status.
**Fix:** Map the status code to a matching error code:
- 400 → `"BAD_REQUEST"`
- 401 → `"UNAUTHORIZED"`
- 403 → `"FORBIDDEN"`
- 404 → `"NOT_FOUND"`
- 500 → `"INTERNAL_ERROR"`
Use a `switch` or small helper method.

### TASK 1.8 — Fix AuthRateLimitFilter IP Spoofing (HIGH)

**File:** `.../security/AuthRateLimitFilter.java`, lines ~137-143
**Root cause:** Trusts `X-Forwarded-For` header from any client.
**Fix:** Only read `X-Forwarded-For` if `request.getRemoteAddr()` matches a known
proxy IP (from a configurable allowlist, e.g., `TRUSTED_PROXIES` env var).
Otherwise, use `request.getRemoteAddr()` directly.

### TASK 1.9 — Fix Fallback Rate Limiter (HIGH)

**File:** `.../security/ApiKeyAuthFilter.java`, lines ~199-203
**Root cause:** `System.currentTimeMillis() % 6000 < 600` is not rate limiting —
it's random 10% admission with no state.
**Fix:** Implement a proper in-memory sliding window:
```
ConcurrentHashMap<String, Deque<Long>> memoryBuckets = new ConcurrentHashMap<>();
```
Track timestamps per user. Allow N requests per minute window. Clean up stale entries.

### TASK 1.10 — Fix Webhook @RequestBody Size (HIGH)

**File:** `.../controller/WebhookController.java`, line ~67
**Root cause:** `@RequestBody String payload` reads the entire body with no size limit.
**Fix:** Configure the request size limit in `application.yml`:
```
spring:
  servlet:
    multipart:
      max-request-size: 1MB
```
Or register a `OncePerRequestFilter` that returns 413 if `request.getContentLength()`
exceeds the limit, BEFORE the body is read.

### TASK 1.11 — Fix Dual API Key System (HIGH)

**File:** `.../service/UserService.java` (lines ~73-81) and `.../service/ApiKeyService.java`
**Root cause:** `UserService.generateApiKey()` writes to legacy `users` table columns
that the auth filter never reads. Keys created this way silently don't work.
**Fix:**
1. Deprecate `UserService.generateApiKey()` — have it delegate to `ApiKeyService.createKey()`.
2. Keep the legacy columns in the entity for backward compatibility (don't delete them),
   but stop writing to them.
3. Add a comment explaining the legacy columns are read-only.

### TASK 1.12 — Fix JWT Blacklist Audit Logging (MEDIUM)

**File:** `.../security/JwtBlacklistService.java`, line ~50
**Root cause:** `System.out.println()` bypasses SLF4J/logback.
**Fix:** Replace with `log.warn("[AUDIT] Token blacklisted: jti={}, userId={}, reason={}", ...)`

### TASK 1.13 — Fix System.err.println in AuthController (MEDIUM)

**File:** `.../controller/AuthController.java`, lines ~225, 229
**Root cause:** `System.err.println()` bypasses SLF4J.
**Fix:** Replace with `log.error("Failed to clean up refresh token", e)` and `log.error("Failed to blacklist token", e)`.

### TASK 1.14 — Make Webhook Processing Transactional (MEDIUM)

**File:** `.../service/WebhookService.java`, line ~71
**Root cause:** `@Async` method is not `@Transactional`.
**Fix:** Add `@Transactional` to `processAsync()`. Ensure the async thread has a
transaction manager configured (Spring's `@Async` + `@Transactional` work together
when using `@EnableAsync` with a proper executor).

### TASK 1.15 — Add Rejection Handler to Async Thread Pool (MEDIUM)

**File:** `.../config/AsyncConfig.java`, lines ~20-29
**Root cause:** No `RejectedExecutionHandler` — saturated tasks are silently dropped.
**Fix:**
```java
new ThreadPoolExecutor.CallerRunsPolicy()
```
or a custom handler that logs the rejection and increments a metric counter.
`CallerRunsPolicy` is the safest choice — it runs the task on the calling thread,
providing backpressure.

### TASK 1.16 — Add WebSocket Timeouts to GitHubService (MEDIUM)

**File:** `.../service/GitHubService.java` (all WebClient calls)
**Root cause:** No timeout on WebClient calls. A slow GitHub API blocks threads indefinitely.
**Fix:** Configure the WebClient with response and connect timeouts:
```java
HttpClient.create()
    .responseTimeout(Duration.ofSeconds(30))
    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
```
Replace the raw `WebClient.builder()` with one using this `HttpClient`.

### TASK 1.17 — Fix ApiKeyAuthFilter 403 → 401 (HIGH)

**File:** `.../security/ApiKeyAuthFilter.java`, line ~207
**Root cause:** Returns 403 (Forbidden) for invalid credentials. Should be 401.
**Fix:** Change `SC_FORBIDDEN` to `SC_UNAUTHORIZED` and add
`response.setHeader("WWW-Authenticate", "Bearer")`.

### TASK 1.18 — Add Startup Validation for COOKIE_SECURE (MEDIUM)

**File:** `.../config/AppConfig.java` or a new `.../config/StartupValidation.java`
**Root cause:** No warning when `COOKIE_SECURE=false` in production.
**Fix:** Add a `@PostConstruct` method that checks: if `cookieSecure` is false
and the active profile is not `dev`/`test`, log a WARN: "COOKIE_SECURE is disabled
in a non-dev profile. Auth cookies will be sent over unencrypted HTTP."

### TASK 1.19 — Fix Open Redirect in OAuth Callback (MEDIUM)

**File:** `.../controller/AuthController.java`, line ~120
**Root cause:** `frontendUrl` is used directly without domain validation.
**Fix:** Validate `appConfig.getFrontendUrl()` at startup. If it doesn't match
an expected pattern (same host as the configured frontend domain), throw an
`IllegalStateException`. At minimum, validate that it is an absolute URL with
`http(s)://` scheme and a non-localhost host in production.

### TASK 1.20 — Fix WebSocket Secret Caching (LOW)

**File:** `.../webhook/HmacVerifier.java`, line ~65
**Root cause:** Decrypts the webhook secret on every request.
**Fix:** Cache the decrypted secret in a `ConcurrentHashMap<Long, String>`
keyed by repo ID. Invalidate the cache entry when a repo's webhook secret is
rotated (hook into `RepoService`).

### TASK 1.21 — Fix LogRedactorFilter (LOW-MEDIUM)

**File:** `.../logging/LogRedactorFilter.java`
**Root cause:** Logs violations but doesn't actually redact sensitive headers
from downstream access logs.
**Fix:** Wrap the request with a custom `HttpServletRequestWrapper` that returns
redacted values for sensitive headers (`Authorization`, `Cookie`, `X-Api-Key`).
The wrapper's `getHeaders()` and `getHeader()` should return `"[REDACTED]"` for
those header names.

### TASK 1.22 — Fix SecureRandom Thread Safety (LOW-MEDIUM)

**File:** `.../security/EncryptionService.java` and `.../service/UserService.java`
**Root cause:** Single `SecureRandom` instance shared across threads.
**Fix:** Use `ThreadLocal<SecureRandom>`:
```java
private static final ThreadLocal<SecureRandom> RANDOM = 
    ThreadLocal.withInitial(SecureRandom::new);
```

### TASK 1.23 — Add Redis Authentication (HIGH)

**File:** `infra/docker-compose.yml` and `infra/docker-compose.secrets.yml`
**Root cause:** Redis has no password.
**Fix:**
1. In `docker-compose.secrets.yml`, add a `redis_password` secret.
2. In `docker-compose.yml`, add to the redis service:
   `command: ["redis-server", "--requirepass", "/run/secrets/redis_password"]`
3. In the API service, pass `SPRING_DATA_REDID_PASSWORD` from the secret.
4. In dev mode, keep it simple: use a default dev password.

### TASK 1.24 — Add `@EnableScheduling` If Missing (LOW)

**File:** Check `.../CodeLensApplication.java` and all `@Configuration` classes
**Root cause:** `@Scheduled` on `SecurityMonitor.resetMetrics()` requires `@EnableScheduling`.
**Fix:** If no class has `@EnableScheduling`, add it to `CodeLensApplication.java`
or create a dedicated `SchedulingConfig` class.

### TASK 1.25 — Fix show-sql for Production (MEDIUM)

**File:** `apps/api/src/main/resources/application.yml`
**Root cause:** `show-sql` is set in dev profile but not explicitly disabled for prod.
**Fix:** In the main `application.yml`, set `spring.jpa.show-sql: false`.
The dev profile can override to `true`.

---

## SERVICE 2: ML WORKER (FastAPI / Python) — `apps/ml-worker/`

### TASK 2.1 — Fix Broken Test Dependency (HIGH)

**File:** `apps/ml-worker/requirements-test.txt`, line 4
**Root cause:** `httpx2==2.4.0` — the package `httpx2` does not exist on PyPI.
**Fix:** Change to `httpx==2.4.0`.

### TASK 2.2 — Wire Up the `mode` Field or Remove It (CRITICAL)

**File:** `apps/ml-worker/app/schemas.py` and `app/main.py`
**Root cause:** `mode: "diff" | "file"` is accepted in the schema but never read.
Sending `"mode": "file"` silently produces diff-only analysis.
**Fix (option A — implement):** In `main.py` line ~113, branch on `req.mode`:
- `"diff"` → existing behavior (analyze `req.diff`)
- `"file"` → fetch the full file content from the API, run analysis on the entire file
**Fix (option B — simplify):** Remove `mode` from `ReviewRequest` schema entirely
if file-mode analysis is not planned. Update the API docs.

### TASK 2.3 — Add Request Timeout to Inference (CRITICAL)

**File:** `apps/ml-worker/app/main.py`, line ~113
**Root cause:** `model.predict()` runs with no timeout. A slow model or large diff
can hold the handler indefinitely.
**Fix:** Wrap the predict call with `asyncio.wait_for()`:
```python
findings = await asyncio.wait_for(
    asyncio.to_thread(model.predict, req.diff, req.language),
    timeout=45.0
)
```

### TASK 2.4 — Add CORS Middleware (MEDIUM)

**File:** `apps/ml-worker/app/main.py`
**Root cause:** No CORS configured. If the web frontend is on a different origin,
browser blocks all requests.
**Fix:** Add FastAPI CORS middleware:
```python
from fastapi.middleware.cors import CORSMiddleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=[os.environ.get("FRONTEND_URL", "http://localhost:3000")],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
```

### TASK 2.5 — Add Rate Limiting on `/ml/review` (MEDIUM)

**File:** `apps/ml-worker/app/main.py`
**Root cause:** No concurrency guard. A single client can exhaust GPU/CPU.
**Fix:** Use `asyncio.Semaphore(max_concurrent=4)` (or a configurable value).
Wrap the predict call: `async with semaphore: findings = model.predict(...)`.

### TASK 2.6 — Fix `evaluate.py` Hardcoded `model_source` (CRITICAL)

**File:** `apps/ml-worker/training/evaluate.py`, line ~326
**Root cause:** Always writes `HF_REPO` to the report, even when model was loaded
from local `OUTPUT_DIR`.
**Fix:** Capture the return value from `model_predict()` (which returns the actual
source) and use that value in the report.

### TASK 2.7 — Fix `or True` in evaluate.py (HIGH)

**File:** `apps/ml-worker/training/evaluate.py`, line ~162
**Root cause:** `if os.environ.get("HF_TOKEN") or True:` is always true.
**Fix:** Remove `or True`. The intent was to try Hub with cached auth if available,
fall back to local otherwise. The correct logic:
```python
hf_token = os.environ.get("HF_TOKEN")
if hf_token:
    # try hub with token
elif Path(token_cache).exists():
    # try hub with cached auth
else:
    # load from OUTPUT_DIR
```

### TASK 2.8 — Fix Relative Paths in Audit Scripts (MEDIUM)

**Files:** `training/audit_leakage.py`, `training/audit_labels.py`, `training/audit_threshold.py`
**Root cause:** `Path("training/data")` depends on CWD. Must be run from repo root.
**Fix:** Use the same pattern as `evaluate.py`:
```python
REPO_ROOT = Path(__file__).resolve().parents[1]
DATA_PATH = REPO_ROOT / "training" / "data"
```

### TASK 2.9 — Fix `or` vs `is not None` in model.py (MEDIUM)

**File:** `apps/ml-worker/app/model.py`, line ~88
**Root cause:** `self.max_seq_length = max_seq_length or settings.MAX_SEQ_LENGTH`
rejects `max_seq_length=0` (a valid value that falls back to default).
**Fix:** Use `is not None` like the sibling parameters:
```python
self.max_seq_length = max_seq_length if max_seq_length is not None else settings.MAX_SEQ_LENGTH
```

### TASK 2.10 — Remove Dead Regex `_DEV_NULL_RE` (MEDIUM)

**File:** `apps/ml-worker/app/diff_parser.py`, lines ~54-55
**Root cause:** `_DEV_NULL_RE` is identical to `_GIT_HEADER_RE` and never used.
**Fix:** Remove `_DEV_NULL_RE`. If `/dev/null` diff paths need special handling,
implement it properly with a distinct regex that matches `b/dev/null` or `a/dev/null`.

### TASK 2.11 — Fix `pad_token_id` None Safety (LOW)

**File:** `apps/ml-worker/app/tokenizer_utils.py`, line ~87
**Root cause:** `tokenizer.pad_token_id` can be `None` for tokenizers without padding.
**Fix:** Add a guard:
```python
pad_id = tokenizer.pad_token_id
if pad_id is None:
    pad_id = tokenizer.eos_token_id  # fallback
ids = ids + [pad_id] * pad
```

### TASK 2.12 — Fix Duplicate Import in model.py (LOW)

**File:** `apps/ml-worker/app/model.py`, lines ~84, 95
**Root cause:** `from transformers import ...` appears twice in `__init__`; the first
one has `# noqa: F401` (unused import).
**Fix:** Remove the first (unused) import at line ~84.

### TASK 2.13 — Fix Deprecated `evaluation_strategy` (LOW)

**File:** `apps/ml-worker/training/train.py`, line ~251
**Root cause:** `evaluation_strategy` is deprecated in recent transformers versions.
**Fix:** Change to `eval_strategy="epoch"`.

### TASK 2.14 — Initialize `last_windows_processed` in `__init__` (LOW)

**File:** `apps/ml-worker/app/model.py`
**Root cause:** `self.last_windows_processed` is only set on first `predict()` call.
Accessing it before raises `AttributeError`.
**Fix:** Add `self.last_windows_processed = 0` in `__init__`.

---

## SERVICE 3: WEB (Next.js / TypeScript) — `apps/web/`

### TASK 3.1 — Fix Hardcoded Localhost OAuth URL (CRITICAL)

**File:** `apps/web/src/app/page.tsx`, line ~68
**Root cause:** `<a href="http://localhost:8080/api/auth/github">` is hardcoded.
In production, this points to a non-existent address and uses HTTP.
**Fix:** Use the env var:
```tsx
<a href={`${process.env.NEXT_PUBLIC_API_BASE_URL}/api/auth/github`}>
```

### TASK 3.2 — Fix AuthShell Error Handling (CRITICAL)

**File:** `apps/web/src/components/AuthShell.tsx`, lines ~40-47
**Root cause:** All errors from `getMe()` are treated as 401. The error is discarded.
**Fix:**
1. Check `err instanceof ApiError` and inspect `err.status`.
2. On 401: redirect to `/` (unauthenticated).
3. On 403: show "Access denied" state.
4. On 5xx / network error: show a retry button with the error message.
5. Log the error to console or an error-tracking service.

### TASK 3.3 — Guard `repoId` Against Undefined (HIGH)

**Files:** `apps/web/src/app/repo/[repoId]/page.tsx`, `apps/web/src/app/pr/[prId]/page.tsx`
**Root cause:** `useParams()` can return `undefined` during navigation.
**Fix:** Add a guard immediately after destructuring:
```tsx
const repoId = params.repoId;
if (!repoId) {
  return <div>Repository not found.</div>;
}
```

### TASK 3.4 — Fix `rel` on External Links (HIGH)

**Files:** `apps/web/src/app/pr/[prId]/page.tsx`, `apps/web/src/app/repo/[repoId]/page.tsx`,
`apps/web/src/components/ConnectRepoModal.tsx`
**Root cause:** `rel="noreferrer"` is missing `noopener`. Older browsers may expose
`window.opener` to the linked page (tabnapping).
**Fix:** Change all instances of `rel="noreferrer"` to `rel="noopener noreferrer"`.

### TASK 3.5 — Add CSP Security Headers (HIGH)

**File:** `apps/web/next.config.mjs` (create if missing)
**Root cause:** No CSP headers. No `next.config.js` exists for security headers.
**Fix:** Create or update `next.config.mjs`:
```js
import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

export function middleware(request: NextRequest) {
  const response = NextResponse.next();
  response.headers.set('Content-Security-Policy',
    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none';");
  response.headers.set('X-Frame-Options', 'DENY');
  response.headers.set('X-Content-Type-Options', 'nosniff');
  return response;
}

export const config = {
  matcher: ['/((?!_next/static|_next/image|favicon.ico).*)'],
};
```

### TASK 3.6 — Fix Sparkline Gradient ID Collision (MEDIUM)

**File:** `apps/web/src/components/QualitySparkline.tsx`, line ~72
**Root cause:** `id={'spark-' + stroke}` is not unique across multiple instances.
**Fix:** Include a unique suffix: `id={'spark-' + repoId + '-' + stroke}`

### TASK 3.7 — Deduplicate `formatAntiPattern` (MEDIUM)

**Files:** `apps/web/src/lib/utils.ts` (canonical) vs `apps/web/src/components/QualityChart.tsx`
**Root cause:** Two implementations behave differently.
**Fix:** In `QualityChart.tsx`, remove the inline version and import from `utils.ts`.

### TASK 3.8 — Fix AuthShell useEffect Dependencies (MEDIUM)

**File:** `apps/web/src/components/AuthShell.tsx`, line ~51
**Root cause:** `router` in the dependency array can cause re-auth flashes.
**Fix:** Remove `router` from the dependency array. Only depend on `pathname`.

### TASK 3.9 — Disable `revalidateOnFocus` on Dashboard (MEDIUM)

**File:** `apps/web/src/app/dashboard/page.tsx`, line ~43
**Root cause:** `revalidateOnFocus: true` causes unnecessary refetches.
**Fix:** Change to `revalidateOnFocus: false` for consistency with other pages.

### TASK 3.10 — Clamp Confidence Percentage (LOW)

**File:** `apps/web/src/components/FindingCard.tsx`, line ~31
**Fix:** `const confPct = Math.min(100, Math.max(0, Math.round(finding.confidence * 100)));`

### TASK 3.11 — Remove `connectNulls` from Quality Chart (LOW)

**File:** `apps/web/src/components/QualityChart.tsx`, line ~224
**Fix:** Remove `connectNulls` from the quality score `Line` component.
The `Area` for `criticalCount` already doesn't have it — this makes them consistent.

### TASK 3.12 — Fix API Key Auto-Hide (MEDIUM)

**File:** `apps/web/src/app/settings/page.tsx`, lines ~277-279
**Root cause:** Full API key stays in DOM and React state indefinitely.
**Fix:**
1. After displaying the key, start a 30-second `setTimeout`.
2. After timeout, clear the key from state (replace with `"••••••••"`).
3. Show a countdown: "Key will be hidden in 30s..."

---

## SERVICE 4: VS CODE EXTENSION — `apps/vscode-ext/`

### TASK 4.1 — Create Missing `package.json` (HIGH)

**File:** `apps/vscode-ext/package.json` (create)
**Root cause:** No `package.json` manifest. The extension cannot be installed or packaged.
**Fix:** Create a proper VS Code extension `package.json` with:
- `engines.vscode`
- `contributes.configuration`
- `activationEvents`
- `main` entry point
- Dependencies (`vscode`, `undici` or `node-fetch`)

### TASK 4.2 — Add Request Timeout (CRITICAL)

**File:** `apps/vscode-ext/src/reviewer.ts`, line ~105
**Root cause:** `fetch()` has no timeout. Extension can hang forever.
**Fix:** Use `AbortSignal.timeout` (30 seconds):
```typescript
const response = await fetch(buildUrl(), {
  ...,
  signal: AbortSignal.timeout(30_000),
});
```
Handle `AbortError` in the catch block to restore previous diagnostics state.

### TASK 4.3 — Validate API Response Shape (HIGH)

**File:** `apps/vscode-ext/src/reviewer.ts`, lines ~125, 157
**Root cause:** Response is cast with `as ScanFileResponse` but never validated.
If `findings` is undefined, `.map()` throws `TypeError`.
**Fix:** After parsing JSON, validate:
```typescript
if (!response || !Array.isArray(response.findings)) {
  throw new Error(`Invalid API response: expected { findings: [...] }`);
}
```

### TASK 4.4 — Fix Zero-Width Range (HIGH)

**File:** `apps/vscode-ext/src/reviewer.ts`, lines ~176-184
**Root cause:** `Range(0, 0, 0, 0)` is zero-width — diagnostic is invisible.
**Fix:** Change to `new Range(0, 0, 0, 1)` (1-character range at start of line 0).
Also: add `Range` to the top-level import instead of using `require("vscode").Range`.

### TASK 4.5 — Replace `require("vscode")` with Import (HIGH)

**File:** `apps/vscode-ext/src/reviewer.ts`, lines ~183-184
**Root cause:** `require("vscode")` is used despite `vscode` being imported at the top.
**Fix:** Add `Range` to the destructured import: `import { ..., Range } from "vscode"`.
Then use `new Range(0, 0, 0, 1)` directly.

### TASK 4.6 — Warn on Non-HTTPS Remote URLs (HIGH)

**File:** `apps/vscode-ext/src/reviewer.ts`, line ~29
**Root cause:** API key sent in plaintext if user configures `http://remote-server`.
**Fix:** At activation/start, check `config.apiUrl`. If it starts with `http://` and
the host is not `localhost` or `127.0.0.1`, show a warning:
```typescript
const url = new URL(config.apiUrl);
if (url.protocol === 'http:' && !['localhost', '127.0.0.1'].includes(url.hostname)) {
  vscode.window.showWarningMessage('API key is sent over unencrypted HTTP.');
}
```

### TASK 4.7 — Add Request Deduplication (MEDIUM)

**File:** `apps/vscode-ext/src/reviewer.ts`, lines ~79-136
**Root cause:** Every save fires a new request. Rapid saves create parallel requests.
**Fix:** Track in-flight requests per document URI:
```typescript
const inFlight = new Map<string, { promise: Promise<void>; controller: AbortController }>();
```
On new scan: abort previous request for that URI, then start a new one.

### TASK 4.8 — Add File Size Guard (MEDIUM)

**File:** `apps/vscode-ext/src/reviewer.ts`, line ~112
**Root cause:** `doc.getText()` reads the entire file with no size check.
**Fix:** Before sending:
```typescript
const MAX_BYTES = 1 * 1024 * 1024; // 1 MB
if (doc.getText().length > MAX_BYTES) {
  statusBarItem.text = '$(alert) File too large to scan';
  return;
}
```

### TASK 4.9 — Handle Unknown Severity (MEDIUM)

**File:** `apps/vscode-ext/src/reviewer.ts`, lines ~203-212
**Root cause:** Unknown severity strings silently downgrade to `Information`.
**Fix:** Add a default case that logs a warning and defaults to `Warning` (not `Information`):
```typescript
default:
  console.warn(`Unknown severity: ${severity}`);
  diagnosticSeverity = DiagnosticSeverity.Warning;
```

### TASK 4.10 — Validate Confidence Value (MEDIUM)

**File:** `apps/vscode-ext/src/reviewer.ts`, lines ~191-193
**Fix:** `const pct = Math.min(100, Math.max(0, Math.round(f.confidence * 100)));`

### TASK 4.11 — Sanitize Error Messages Shown to User (LOW)

**File:** `apps/vscode-ext/src/reviewer.ts`, lines ~118-132
**Root cause:** API error body (truncated to 200 chars) is shown to the user.
**Fix:** Show a generic message to the user: `Scan failed: server error`.
Log the full body to the extension's `OutputChannel` for debugging.

---

## SERVICE 5: INFRA & CI — `infra/`, `.github/`, `github-action/`

### TASK 5.1 — Add Redis Authentication (HIGH)

**File:** `infra/docker-compose.yml` and `infra/docker-compose.secrets.yml`
**Fix:** (Covered in TASK 1.23 above, infra-specific implementation.)

### TASK 5.2 — Fix `httpx2` Package Name (HIGH)

**File:** `apps/ml-worker/requirements-test.txt`
**Fix:** `httpx2==2.4.0` → `httpx==2.4.0`

### TASK 5.3 — Fix GitHub Action HTTPS Validation (HIGH)

**File:** `github-action/index.js`
**Root cause:** `/^https?:\/\//i` allows HTTP.
**Fix:** Change to `/^https:\/\//i` to require HTTPS only.

### TASK 5.4 — Add Request Timeout to GitHub Action (MEDIUM)

**File:** `github-action/index.js`
**Fix:** Add `signal: AbortSignal.timeout(30_000)` to the fetch call.

### TASK 5.5 — Remove CI Secret Fallbacks (MEDIUM)

**File:** `.github/workflows/ci-api.yml`
**Root cause:** Hardcoded fallback secrets when repo secrets aren't configured.
**Fix:** Replace `|| 'fallback_value'` patterns with explicit `if:` guards:
```yaml
if: ${{ secrets.JWT_SECRET_TEST != '' }}
```
Or fail the job with a clear message if secrets are missing.

### TASK 5.6 — Add npm/pip Audit to CI (MEDIUM)

**Files:** `.github/workflows/ci-web.yml`, `.github/workflows/ci-ml-worker.yml`
**Fix:** Add after install steps:
```yaml
# In ci-web.yml:
- run: npm audit --audit-level=high

# In ci-ml-worker.yml:
- run: pip audit
```

### TASK 5.7 — Enable `typedRoutes` in Next.js Config (LOW)

**File:** `apps/web/next.config.mjs`
**Fix:** Change `typedRoutes: false` to `typedRoutes: true`.

### TASK 5.8 — Fix Dockerfile chown/chmod Order (LOW)

**File:** `apps/api/Dockerfile`
**Root cause:** `chown`/`chmod` run after `USER codelens`.
**Fix:** Move `RUN chown -R codelens:codelens /app` and `RUN chmod -R 500 /app/app.jar`
to BEFORE the `USER codelens` line.

---

## VERIFICATION CHECKLIST

After completing all tasks, verify:

- [ ] `./mvnw compile` succeeds in `apps/api/`
- [ ] `pytest` passes in `apps/ml-worker/`
- [ ] `npm run lint` passes in `apps/web/`
- [ ] `npm run build` succeeds in `apps/web/`
- [ ] `docker compose config` validates in `infra/`
- [ ] No `System.err.println` or `System.out.println` in Java source
- [ ] No `require()` calls in TypeScript source
- [ ] No hardcoded `localhost` URLs in source (only in `.env` files)
- [ ] No real secrets in `.env.example`
- [ ] `.env` is in `.gitignore` and not tracked
- [ ] `infra/.env` is in `.gitignore`
- [ ] `resilience4j-spring-boot3` is in `pom.xml` (not `-boot2`)
- [ ] `httpx` (not `httpx2`) in `requirements-test.txt`

---

## NOTES FOR THE ENGINEER

- This prompt is designed to be dropped directly into Claude Code as a single prompt.
- If any task reveals additional related issues, fix them at the same time (root-cause discipline).
- Do NOT create new abstractions or refactor beyond what each task requires.
- Do NOT upgrade dependencies beyond what each task specifies.
- If a task's fix requires a judgment call not covered here, make the safest choice
  and leave a `// TODO: review` comment.
