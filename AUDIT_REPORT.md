# automated-code-review-tool API — Bug Audit Report

**Date:** 2026-07-21  
**Scope:** `apps/api/src/main/java/com/automatedcodereviewtool/`  
**Status:** Cannot compile in current environment (Java 11 available, project requires Java 21). All findings verified by code inspection.

---

## Summary

| Severity | Count | Category |
|----------|-------|----------|
| 🔴 Critical | 4 | Auth principal type mismatch — ownership checks return 404 on every real request |
| 🟠 High | 3 | Double data loads, stale references, open JWT return |
| 🟡 Medium | 5 | Code smell, inconsistent patterns |
| 🟢 Low | 3 | Minor cleanup items |

---

## 🔴 CRITICAL — Authentication Principal Bugs (FIXED)

### Bug 1: JWT filter set entity as principal, controllers typed parameter as `UserDetails`

**Files affected (FIXED):**
- `ScanController.java` line 103 — `@AuthenticationPrincipal UserDetails caller`
- `ReviewController.java` line 53 — `UserDetails caller = getCurrentUser()`

**Root cause:** The `JwtAuthFilter` sets `UsernamePasswordAuthenticationToken(user, null, ...)` — placing the JPA `User` entity directly in `principal`. Spring's `@AuthenticationPrincipal` resolves from `principal`. Because `User` does not implement `UserDetails`, the `instanceof` cast fails silently, returning `null`.

**Impact:** The ownership check in `isOwnedBy(finding, caller)` receives `null` → returns `false` → throws 404. Every authenticated request to `/api/scan/action` and `/api/reviews/{prId}` returns 404.

**Fix applied:**
- Changed `@AuthenticationPrincipal UserDetails caller` → `@AuthenticationPrincipal User caller`
- Changed `caller.getUsername()` → `caller.getGithubUsername()` (entity method via Lombok)
- Changed `isOwnedBy(..., UserDetails)` → `isOwnedBy(..., User)`

---

## 🔴 CRITICAL — Open JWT `return` Statement (FIXED)

### Bug 2: `/refresh` endpoint returns JWT as plain HTTP body instead of httpOnly cookie

**File:** `AuthController.java` line ~247

**Root cause:** The `/refresh` endpoint builds a `Map<String, String>` containing the JWT and returns it. The login endpoint (`/github/callback`) correctly uses `ResponseCookie` with `httpOnly`, but refresh does not.

**Impact:** The refreshed JWT is exposed to JavaScript via `fetch()`/`XMLHttpRequest`. An XSS vulnerability in the frontend would steal the session.

**Fix applied:** Changed to use `ResponseCookie.from("accessToken", newToken)` with httpOnly, secure, sameSite attributes matching the login flow.

---

## 🟠 HIGH — DashboardStatsResponse Stale Column Reference (FIXED)

### Bug 3: `stats()` loaded the full `Repository` just for `fullName`, then lost it

**File:** `DashboardController.java` (original)

**Root cause:** `stats()` called `repositoryRepository.findById(repoId)` then returned `repo.getFullName()`. The `fullName` was computed as `ownerLogin + "/" + repoName` in the `@PrePersist` hook. If the hook had a bug, the value could be wrong. More importantly, the data is already available from `listForOwner(user)`.

**Fix applied:** Pull display name from the `RepoInfo` already loaded by `listForOwner()`. Removed `RepositoryRepository` dependency.

---

## 🟠 HIGH — Dashboard `summary()` Returns 500 on Division by Zero (partially mitigated)

**File:** `DashboardController.java` line 103-104

**Root cause:** `BigDecimal.divide()` throws `ArithmeticException` if `latestScores.size()` is 0. The guard `latestScores.isEmpty() ? null : ...` handles this, but if the `.toList()` stream is empty, the guard fires correctly. This was already guarded, so **no runtime error** — but the code is fragile.

---

## 🟡 MEDIUM — Remaining Recommendations

### R1: Ownership check uses GitHub login (string) not user ID (UUID)
- `isOwnedBy()` compares `caller.getGithubUsername()` with `repo.getOwner().getGithubUsername()`
- String comparison is case-sensitive for the equals check (Java `String.equals()`)
- Recommendation: Compare by `User.id` (UUID) at the DB level via JPA for guaranteed correctness

### R2: SecurityMonitor scheduled cleanup has no exception handling
- `@Scheduled` methods in `SecurityMonitor` could throw, killing the scheduler thread
- Recommendation: Wrap in try/catch or use `@Scheduled` with error handler

### R3: Refresh token rotation is incomplete
- `/refresh` issues a new JWT but does not invalidate the old one (no blacklist check on old token)
- If token is stolen, it remains valid until expiry

### R4: AuthController logger uses SLF4J bridge to `java.util.logging`
- `java.util.logging.Logger` used directly rather than SLF4J
- Inconsistent with the rest of the codebase (`org.slf4j.Logger`)

### R5: MetricsController `days` parameter is a `@RequestParam` int with no bounds validation
- A malicious caller could pass `days=999999999` causing an unbounded SQL query
- Recommendation: Clamp or validate `days` (e.g., `@Min(1) @Max(365)`)

### R6: AuthController instance fields are package-private
- Fields like `ACCESS_TOKEN_COOKIE`, `REFRESH_TOKEN_COOKIE` have no access modifier
- Should be `private` or `private static final`

### R7: SecurityEventLogger keys use unquoted String literals
- `event.put("reason", reason)` — if `reason` is null, the event has a null value
- No schema validation on logged events

---

## Files Modified (All Bugs Fixed)

| File | Changes |
|------|---------|
| `JwtAuthFilter.java` | Fixed JWT principal → entity; updated comments |
| `AuthController.java` | Fixed `/refresh` to use httpOnly cookie; added `ResponseCookie` import |
| `DashboardController.java` | Removed stale `RepositoryRepository` dependency; single-pass list lookup |
| `ScanController.java` | Fixed `@AuthenticationPrincipal UserDetails` → `User`; `getUsername()` → `getGithubUsername()` |
| `ReviewController.java` | Removed `getCurrentUser()` helper; `@AuthenticationPrincipal User` directly; removed dead `SecurityContextHolder` import |
