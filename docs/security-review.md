# Security review: authentication and application security

Scope: the Java API's authentication, session, CSRF/origin, cookie, API-key,
rate-limit, header, CORS, error-handling and logging controls, as of branch
`agent/security` (base `821a418`). Requirement IDs are from
[OWASP ASVS 5.0.0](https://github.com/OWASP/ASVS/tree/v5.0.0/5.0/en); test
cases follow the [OWASP WSTG](https://owasp.org/www-project-web-security-testing-guide/).
Every ID cited below was read against the ASVS 5.0.0 text.

Classification used in the findings table:

- **confirmed defect**: reproduced by a test or follows deterministically from code or a browser specification
- **security risk requiring validation**: plausible, but exploitation depends on conditions not demonstrated here
- **capability limitation**: works as designed, but the design leaves a gap
- **enhancement proposal**: hardening that is not a fix for a demonstrated weakness

## 1. System and trust boundaries

```
browser ──(1)──> web (Next.js, *-web.onrender.com) ──(2) proxy /api/*──> API (Spring, *-api.onrender.com)
                                                                          │ ├─(3)─> GitHub (OAuth, REST)
VS Code ext / GitHub Action ──(4) Bearer cl_live_…──────────────────────>│ ├─(5)─> ML worker (shared secret)
GitHub webhooks ──(6) HMAC-SHA256──────────────────────────────────────>│ ├─> PostgreSQL
                                                                            └─> Redis (sessions, blacklist, rate limits)
```

Assets: GitHub OAuth tokens (AES-256-GCM at rest; `repo` scope gives read and
write access to every repository the user can reach), session JWTs, API keys
(bcrypt), webhook secrets, `JWT_SECRET`, `ENCRYPTION_KEY`, `ML_WORKER_SECRET`,
private source code in PR diffs.

Actors considered:

- A cross-site attacker page (any origin). Browsers send no `SameSite=Lax` cookies on its subresource requests.
- A same-site attacker: a sibling subdomain under a custom domain (`blog.example.com` next to `app.example.com`). Browsers DO send Lax cookies on its requests, and it can try to plant cookies with `Domain=example.com`.
- An authenticated user trying to reach another user's data. Covered by the owner-scoped controller tests, which are out of scope here.
- A network attacker. TLS and HSTS are assumed.
- A stolen API key or session token.

## 2. Browser behaviour and CSRF analysis (WSTG-SESS-05, WSTG-CLNT-07)

Session cookies are `HttpOnly; SameSite=Lax; Path=/`, host-only (no `Domain`),
`Secure` and `__Host-`-prefixed when `COOKIE_SECURE=true`. CSRF tokens are
disabled in Spring Security. `JwtAuthFilter` accepts the access-token cookie.

### 2.1 Current deployment (`render.yaml` at 821a418): web and API on different `onrender.com` hosts

`onrender.com` is on the Public Suffix List, so `x-web.onrender.com` and
`x-api.onrender.com` are different *sites*. The dashboard calls the API with
`fetch(..., {credentials: "include"})`. That is a cross-site subresource
request, and under RFC 6265bis browsers do not attach `SameSite=Lax` cookies to
it. The API sets its cookies during the top-level OAuth redirect, but the
browser never sends them back on the dashboard's fetches. Every
`/api/auth/me` call returns 401 and the dashboard cannot hold a session.
Browsers that block third-party cookies fail the same way. **Consequence:**
CSRF from other sites is moot in this topology, and the topology itself is
broken (SEC-01). The fix is contract item 7: the web app proxies `/api/*`
same-origin, so the cookies belong to the web origin and are first-party.

### 2.2 Target deployment: same-origin proxy

The browser talks only to the web origin, and the proxy forwards `Origin`,
`Referer`, `Sec-Fetch-*` and `Cookie` unchanged. For requests from other sites:

| Request an attacker page can make | Lax cookie sent? | CORS preflight? | Reached handler at 821a418? | Now |
| --- | --- | --- | --- | --- |
| cross-site `fetch` POST/DELETE (any body) | no | n/a | no (no cookie: 401) | 401 / 403 |
| cross-site top-level form POST | no (Lax only covers top-level GET) | no | no | 401 / 403 |
| same-site sibling `fetch` POST, no body or `text/plain` (CORS-simple) | **yes** | no | **rejected by Spring's CORS processor** (Origin present and not allowed: 403 "Invalid CORS request") | 403 `CSRF_ORIGIN_REJECTED` |
| same-site sibling form POST (`application/x-www-form-urlencoded` / `text/plain`) | **yes** | no | rejected by CORS processor (browsers send `Origin` on form POSTs) | 403 `CSRF_ORIGIN_REJECTED` |
| same-site sibling `fetch` with `Content-Type: application/json`, PUT, DELETE | yes | **yes**; preflight fails (403, no `Access-Control-Allow-Origin`) | no | no |
| any unsafe request that carries the cookie but **no `Origin`** | yes | no | **yes: reached the handler (204)** | 403 `CSRF_ORIGIN_REJECTED` |

Notes:

- Spring's `DefaultCorsProcessor` rejects an actual (non-preflight) request whose `Origin` header is present, differs from the request's own origin and is not in the allow-list. Before this change that was the only CSRF barrier against same-site attackers. Nothing documented it, and it depended on the CORS configuration staying strict. `CsrfOriginIntegrationTest#corsAloneDoesNotStopRequestsThatOmitOrigin` pins both halves of this behaviour.
- CORS-simple state-changing endpoints (no preflight): `POST /api/auth/logout`, `POST /api/auth/refresh`, `POST /api/auth/logout-all` (bodyless). The JSON endpoints (`/api/repos/connect`, `/api/scan/action`, `/api/auth/api-keys`) refuse `text/plain` and form bodies before any handler runs (`jsonEndpointsIgnoreCorsSimpleContentTypesEvenFromAnAllowedOrigin`). Before the backend fix this refusal surfaced as a 500 (SEC-11). `DELETE` always needs a preflight.
- Impact of forging the bodyless endpoints: forced logout or forced logout-all (denial of service against the victim's session) and a forced refresh rotation (harmless, because the attacker cannot read the response).
- Modern browsers send `Origin` on every cross-origin `fetch`/XHR POST and on form POSTs. So "no `Origin`" (the last row) is not shown to be reachable from a current browser. It is classified as a risk requiring validation (SEC-02), not as an exploitable vulnerability.
- State-changing GETs (ASVS 3.5.3): none except the OAuth endpoints. `GET /api/auth/github` only sets a fresh `oauth_state` cookie: forcing it can at worst break a login in progress. `GET /api/auth/callback` performs login, but only with a `state` that matches the `__Host-oauth_state` cookie (ASVS 10.2.1). No GET mutates application data.

### 2.3 Control implemented (contract item 7; ASVS 3.5.1, 3.5.2)

`OriginVerificationFilter` runs before the CORS filter. It applies to POST, PUT,
PATCH and DELETE requests that carry an access-token or refresh-token cookie:

1. If `Origin` is present, it must equal an entry of `app.allowed-origins` exactly (scheme, host and port, normalized the way browsers serialize it). `null` never matches.
2. If `Origin` is absent, the request is accepted only with `Sec-Fetch-Site: same-origin`. If there is no `Sec-Fetch-Site` header either, the `Referer`'s origin must be allowed.
3. Anything else gets 403 `{"error":"CSRF_ORIGIN_REJECTED",…}` and a `SECURITY` log event.

Exempt: requests without a session cookie (no ambient credential), requests
with `Authorization: Bearer` (JWT or API key: a browser cannot attach that
header cross-origin without a preflight the API refuses), and the HMAC-verified
`POST /api/webhook/github`.

`app.allowed-origins` (`ALLOWED_ORIGINS`, comma-separated) defaults to the
origin of `app.frontend-url`. CORS uses the same list. Each entry must be a
bare `http(s)://host[:port]` origin. `*`, `null` and values with a path fail at
startup.

Mutation check: with the filter removed from the chain, all 15 negative tests
in `CsrfOriginIntegrationTest` fail. 11 fail because the 403 body is no longer
`CSRF_ORIGIN_REJECTED` (CORS's plain-text 403). 4 fail because the Origin-less
requests reach the handler (204).

## 3. Findings

| ID | Sev | Classification | Finding | Reproduction / evidence | Root cause | Target design | Acceptance test | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| SEC-01 | P1 | confirmed defect (by RFC 6265bis + PSL; not reproduced in a live browser) | Dashboard on `*-web.onrender.com` cannot authenticate to `*-api.onrender.com` | §2.1 | Cross-site credentialed fetch: Lax cookies are not sent | Same-origin `/api/*` proxy (web), `FRONTEND_URL`/`APP_BASE_URL` = web origin | Web proxy tests (owner: web) | API side done; deployment change listed in §6 |
| SEC-02 | P2 | security risk requiring validation | CSRF defence was an undocumented side effect of CORS; unsafe cookie requests without `Origin` reached handlers | `CsrfOriginIntegrationTest` with the filter removed: Origin-less logout returns 204 | No explicit origin check; CSRF disabled | `OriginVerificationFilter` (§2.3) | `CsrfOriginIntegrationTest` (26 cases) | fixed |
| SEC-03 | P2 | security risk requiring validation (custom domains only) | A sibling subdomain could plant `accessToken`/`oauth_state` cookies (`Domain=example.com`), enabling login CSRF into the attacker's account or state fixation | Spec analysis. Not possible under `onrender.com`, because the PSL forbids `Domain=onrender.com` | Unprefixed cookie names | `__Host-` names when `COOKIE_SECURE=true`. Unprefixed names are ignored | `cookieWithoutHostPrefixIsNotASessionInSecureMode`, `stateMismatchIsRejectedWithoutContactingGitHub`, `LocalWebSecurityTest#oauthCookiesRemainSecureInProduction` | fixed |
| SEC-04 | P2 | confirmed defect (handler chain at 821a418; pinned by new tests) | OAuth callback failures rendered API errors in the user's browser. Cancelling consent (`?error=access_denied`, no `code`) hit the catch-all handler: 500 plus an ERROR stack trace. GitHub outages and timeouts: 500. A stale code: 401 JSON | `consentDeniedRedirectsToFrontendInsteadOf500`, `githubOutageDuringLoginRedirects…`, `staleCodeRedirectsWithGithubRejected` | `code` was a required `@RequestParam`, and GitHub exceptions were not caught | Every failure redirects to `${FRONTEND_URL}/?authError=<access_denied\|oauth_state_invalid\|github_unavailable\|github_rejected\|session_unavailable>` and clears the state cookie | same | fixed |
| SEC-05 | P2 | capability limitation | A GitHub grant revoked by the user (or a stored token that can no longer be decrypted) kept refreshing sessions for up to 7 days. GitHub-backed actions then failed later | `refreshWithRevokedGitHubGrantRequiresReauthentication` | Refresh never consulted GitHub. GitHub does not notify OAuth apps of revocation | Refresh calls `GET /user` (5 s cap). 401 or an undecryptable token: refresh session deleted, cookies cleared, 401 `GITHUB_REAUTH_REQUIRED`. Outages and rate limits are inconclusive and do not log users out | `GitHubAuthorizationVerifierTest`, `refreshSurvivesAGitHubOutage`, `refreshWithUndecryptableGitHubTokenRequiresReauthentication` | fixed |
| SEC-06 | P2 | enhancement proposal (ASVS 7.4.1, 7.5.2) | No way to end sessions on other devices. Logout revoked only the current access-token `jti` | - | Stateless JWTs with no per-user generation | `users.token_version` (V24) copied into each JWT as `ver`. `POST /api/auth/logout-all` increments it, which invalidates every access and refresh token. The database is the authority, so no Redis write is needed | `logoutAllRevokesEveryOutstandingAccessAndRefreshToken`, `JwtServiceTest#tokensCarryTheUserTokenVersion` | implemented |
| SEC-07 | P3 | confirmed defect (hygiene; ASVS 6.3.2, 16.2.5) | Spring Boot created an in-memory `user` and logged its generated password at WARN on startup | Observed in the startup log on PG16 (orchestrator) | `UserDetailsServiceAutoConfiguration` activates when no `UserDetailsService` exists. Not usable, because basic and form login are disabled | Declare a `UserDetailsService` that knows no users | `noGeneratedInMemoryUserOrPasswordExists` | fixed |
| SEC-08 | P2 | enhancement proposal (ASVS 10.2.3) | OAuth requests `read:user repo`. `repo` grants full read/write to all private repositories, which exceeds what webhook install, diff read and PR comments strictly need | Code: `GitHubService#getOAuthRedirectUrl` | OAuth Apps have no finer-grained scope for private repositories | `app.github.oauth-scopes` (`GITHUB_OAUTH_SCOPES`). The default keeps private repositories working. Public-only deployments use `public_repo admin:repo_hook`. See §5 | `LocalWebSecurityTest#oauthScopesAreConfigurableAndDefaultToPrivateRepoSupport` | implemented (default unchanged) |
| SEC-09 | P3 | enhancement proposal (ASVS 3.4.3) | API CSP used `default-src 'self'; base-uri 'self'` | `SecurityHeadersFilter` | Generic policy | JSON-only API: `default-src 'none'; frame-ancestors 'none'; object-src 'none'; base-uri 'none'` | `SecurityIntegrationTest#securityHeadersArePresent` | fixed |
| SEC-10 | P3 | security risk requiring validation (ASVS 16.2.5) | Lombok `@Data` `toString()` on `User` included the encrypted GitHub token, and on `ApiKey` the bcrypt hash (plus the user) | No current log statement prints these entities | Generated `toString` | `@ToString.Exclude` on credential fields | `entityToStringNeverIncludesCredentials` | fixed |
| SEC-11 | P2 | confirmed defect (owner: backend) | `GlobalExceptionHandler`'s catch-all turned Spring MVC client errors into 500 + ERROR stack-trace logs (e.g. `text/plain` to a JSON endpoint gave 500 instead of 415) | Observed while running `jsonEndpointsIgnoreCorsSimpleContentTypesEvenFromAnAllowedOrigin` | `@ExceptionHandler(Exception.class)` | Map `ErrorResponse` statuses | backend's `OwnershipIsolationTest` | reported; fixed on `agent/backend` |
| SEC-12 | P2 | security risk requiring validation | The auth rate limiter keys on `True-Client-IP` when the peer is in `10.0.0.0/8`. Whether a client can inject `True-Client-IP` through Render's edge, and whether the web proxy's forwarded value is trustworthy, cannot be verified locally. The Next proxy cannot append to `X-Forwarded-For` | `AuthRateLimitFilter`, `render.yaml` | Platform behaviour | Verify on Render: send a forged `True-Client-IP` and check the limiter key. If it is forgeable, key on the proxy-set header only | manual, on staging | open |
| SEC-13 | P3 | capability limitation | One refresh session per user. A login on a second device overwrites `session:refresh:<id>`, so the first device is logged out at its next refresh (≤15 min) | Code | Single Redis key per user | Per-device session IDs (`session:refresh:<user>:<sid>`), if multi-device use matters | - | documented |
| SEC-14 | P3 | enhancement proposal (ASVS 9.2.3) | JWTs carry no `aud`/`iss`. Signature (HS256 key ≥256 bits, algorithm pinned by `verifyWith(SecretKey)`; ASVS 9.1.1, 9.1.2) and `exp` (9.2.1) are verified | `JwtService` | Single service with a single key, so cross-service replay is not possible today | Add `aud`/`iss` if the key is ever shared | - | documented |
| SEC-15 | P3 | capability limitation | API-key lookup prefix is 32 bits (unique column), so collisions become likely past ~10⁵ keys (insert fails). An unknown prefix skips bcrypt, so timing reveals whether a prefix exists. The remaining 96 secret bits make brute force infeasible. Revocation is checked on every request. The per-user limit applies after verification | `ApiKeyService`, `ApiKeyAuthFilter` | Design | Widen the prefix if key volume grows. Retry on collision | - | documented |
| SEC-16 | P3 | capability limitation | Access tokens stay valid for up to 15 minutes after GitHub revocation (re-auth happens at the next refresh). Logout depends on Redis, which fails closed with 503 | Design | Stateless access tokens | Acceptable. Bounded by the access-token TTL | - | documented |

Verified and found adequate (no change):

- HSTS 1 year + `includeSubDomains` (ASVS 3.4.1). `preload` has no effect on a non-registrable host and does no harm.
- `nosniff` (3.4.4), `Referrer-Policy: strict-origin-when-cross-origin` (3.4.5), `frame-ancestors 'none'` (3.4.6), `Server` header suppressed.
- CORS allow-list is fixed, with credentials (3.4.2).
- `RequestSizeLimitFilter` bounds query, header and declared body size, and also counts streamed bodies.
- The OAuth `state` is 256 bits from `SecureRandom`, single-use, single-request scoped, and now compared in constant time (10.2.1, 10.1.2).
- A new token pair is issued on every login (7.2.4).
- Refresh rotation is an atomic Redis compare-and-set, and reuse revokes the session.
- Token verification is backend-only (7.2.1). `HttpOnly` session cookies are only ever written via `Set-Cookie` (3.3.4).
- Deleted users lose access immediately, because the filter loads the user on every request (7.4.2).
- Generic 500 bodies contain no message, class or stack trace (16.5.1; WSTG-ERRH-01): `unhandledErrorsExposeNoMessageOrStackTrace`.
- Invalid-token errors do not echo the token: `invalidTokenErrorsDoNotEchoTheToken`.
- Only `health`/`info` actuator endpoints are exposed, and health has no component details (13.4.5): `onlyHealthAndInfoActuatorEndpointsAreExposed`.
- `/api/auth/me` never returns the GitHub token.
- The GitHub token is not logged when an OAuth call fails (`githubOutageDuringLoginRedirectsAndDoesNotLogTheToken`). Log statements in the auth paths log exception classes or Redis messages, never tokens.
- Redis outage policy: auth fails closed (503), rate limiters fail open (16.5.2). Login now redirects with `authError=session_unavailable` instead of rendering a 503 page.

## 4. Session model after this change

| Token | Lifetime | Revoked by |
| --- | --- | --- |
| access JWT (`__Host-accessToken`) | 15 min | logout (Redis `jti` blacklist), logout-all (`ver`), user deletion |
| refresh JWT (`__Host-refreshToken`) | 7 days, rotated on use | logout (Redis key delete), reuse detection, logout-all (`ver`), GitHub revocation detected at refresh |
| `__Host-oauth_state` | 5 min, single use | cleared on every callback outcome |

Tokens issued before V24 carry no `ver` claim and count as version 0, so the
deployment itself does not invalidate them. The cookie rename does: browsers
holding the old unprefixed cookies are signed out once after the upgrade.

## 5. OAuth least privilege: `repo` vs `public_repo admin:repo_hook` vs a GitHub App

| Option | Private repos | What a stolen token can do | Cost |
| --- | --- | --- | --- |
| OAuth App, `repo` (default) | yes | read and write code, issues and settings of every repository the user can access | none (current) |
| OAuth App, `public_repo admin:repo_hook` | **no** (diff fetch and comments on private repos fail) | write to public repos, manage hooks | set `GITHUB_OAUTH_SCOPES` |
| GitHub App (installation tokens) | yes, only repositories the user installs it on | `pull_requests:write`, `contents:read`, `metadata:read` on selected repos. Tokens expire after 1 hour. The App's own webhook replaces per-repo hooks | Rebuild: App registration, JWT-signed installation-token minting, installation/webhook events, a user-to-server token for identity, migration of existing repo hooks |

Decision: keep `repo` as the default so private-repository support keeps
working, and make the scope list configurable for public-only deployments.
`read:user` is not needed for `GET /user` (public profile fields are returned
with any token). It is kept in the default only to avoid changing the consent
screen and the existing `GitHubServiceTest`. Removing it is a safe follow-up
for the owner of `GitHubService`. The GitHub App migration is the real
least-privilege fix (ASVS 10.2.3, L3). It is not done here because it replaces
the OAuth-token-per-user model across `RepoService`, `WebhookService` and
`GitHubService`.

## 6. Configuration required for the proxy deployment

API service:

- `FRONTEND_URL=https://<web-host>`. The web origin is the one the browser sees.
- `APP_BASE_URL=https://<web-host>`. This makes the OAuth `redirect_uri` `https://<web-host>/api/auth/callback`.
- `ALLOWED_ORIGINS`: leave unset to use the `FRONTEND_URL` origin. Set a comma-separated list only when the dashboard is served from several origins (for example a custom domain plus the `onrender.com` host).
- `COOKIE_SECURE=true`. This gives `__Host-` cookie names. Use `false` only for local plain-HTTP development, where the names stay `accessToken`/`refreshToken`/`oauth_state`.
- `GITHUB_OAUTH_SCOPES`: optional. Defaults to `read:user repo`.
- `AUTH_TRUSTED_PROXIES=10.0.0.0/8` and `AUTH_CLIENT_IP_HEADER=True-Client-IP` stay as they are. The web service reaches the API over Render's private network, so its peer address is trusted.

GitHub OAuth App: set "Authorization callback URL" to `https://<web-host>/api/auth/callback`.

Web proxy (owner: web):

- Forward `Origin`, `Referer`, `Sec-Fetch-*`, `Cookie` and `True-Client-IP` unchanged.
- Return every `Set-Cookie` and `Location` header unchanged, with no automatic redirect following.
- The "Sign in" link must be the same-origin `/api/auth/github`.
- Handle `?authError=` on `/`.
- Treat refresh 401 `GITHUB_REAUTH_REQUIRED` as "sign in again".

## 7. Residual risks

- SEC-12 (client-IP trust on Render) needs a staging check.
- XSS in the dashboard would defeat the origin check, because the requests would come from the allowed origin. That is outside the API's control and is covered by the web app's CSP and its safe rendering of external links.
- Forced logout and logout-all from an allowed origin are still possible for anyone who can run script there, which is again XSS.
- The `repo` scope remains broad until a GitHub App migration (§5).
- Refresh adds one `GET /user` call to GitHub per active user every 15 minutes (well within GitHub's 5,000 requests/hour per token), and up to 5 s of latency when GitHub is slow.
