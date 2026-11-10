# Engineering & Product Roadmap

This document outlines the deferred, open, and forward-looking engineering milestones for the Automated Code Review Tool (ACRT) following the `release/hardening` release. Each item includes the technical rationale, architectural trade-offs, and observable acceptance criteria.

Related Documentation:
- [Engineering Audit & Findings Tracker](engineering-audit.md)
- [Application Security Review](security-review.md)
- [Review Benchmark Methodology & Results](review-benchmark.md)
- [Production Release Checklist](release-checklist.md)
- [ML System Design & Training](ml-system-design.md)
- [Project Overview & Setup](../README.md)

---

## 1. Roadmap Milestones

### 1.1 GitHub App Migration

- **Category:** Architecture & Security Hardening (ASVS 10.2.3 L3)
- **Priority:** High
- **Tracking:** SEC-08 follow-up

#### Rationale
Currently, ACRT relies on an OAuth App model where authorizing users grant the `repo` scope (`read:user repo`). In GitHub's permission model, an OAuth token with `repo` scope grants full read and write access to all source code, pull requests, issues, and settings across every private and public repository the authorizing user can access. While `app.github.oauth-scopes` now allows public-only installations (`public_repo admin:repo_hook`), private repository analysis still requires broad account-level tokens.

Migrating to a GitHub App replaces user-scoped OAuth tokens with short-lived (1-hour) installation access tokens scoped strictly to repositories where organization or repository owners explicitly install the App. Permissions are granted with least privilege:
- `pull_requests: write` (to inspect pull requests and publish review comments)
- `contents: read` (to fetch diffs and file trees)
- `metadata: read` (to inspect repository metadata)

Additionally, a single GitHub App-level webhook URL replaces individual per-repository webhook registrations, drastically simplifying repository lifecycle management and reducing the risk of orphaned webhooks.

#### Acceptance Criteria
1. ACRT supports GitHub App authentication using private key signing (RS256 JWT) to mint installation access tokens via `POST /app/installations/{installation_id}/access_tokens`.
2. Repository connection workflow supports GitHub App installation events (`installation` and `installation_repositories`), eliminating individual `POST /repos/{owner}/{repo}/hooks` calls.
3. PR diff fetching and PR comment publication use installation tokens instead of user-stored OAuth tokens.
4. User identity is decoupled from repository permissions; dashboard users authenticate via standard GitHub user-to-server OAuth for profile/session identity without needing repository access delegation.
5. Migration path provided for repositories already connected via legacy OAuth webhooks without review interruption or duplicate comments.

---

### 1.2 Model Evaluation Once a Labelled Dataset Exists

- **Category:** Machine Learning & Quality Evaluation
- **Priority:** Medium
- **Tracking:** Follow-up to BNC-01 and ML system design

#### Rationale
ACRT includes a complete CodeBERT multi-label training and serving infrastructure under `apps/ml-worker/training/` and `apps/ml-worker/app/model.py`. However, the tool ships with `MODEL_NAME=none` (serving the deterministic `rule-baseline-v2` scanner) because no publicly vetted, annotated dataset of PR diffs with ground-truth code review findings is packaged with the repository.

To promote any fine-tuned transformer or neural model to production, it must be evaluated on an independent, realistically sized benchmark. Untrained or unvalidated models risk high hallucination rates, erratic false positives on idiomatic constructs, and substantial memory/latency overhead (>100 ms per diff hunk vs. <1 ms for rules).

#### Acceptance Criteria
1. A multi-repository dataset of at least 500 real-world pull requests across Python, JavaScript, TypeScript, and Java is curated and labeled against the 12 taxonomy anti-patterns.
2. Dataset splits are verified using `apps/ml-worker/training/validate_dataset.py` to ensure zero cross-split leakage of identical added lines or hunk hashes across train, validation, and test splits (TRN-01).
3. The benchmark evaluation harness (`benchmarks/run_benchmark.py`) executes with `--engine model` and produces reproducible results in `benchmarks/results/`.
4. The candidate model meets the promotion criteria defined in `docs/ml-system-design.md`:
   - Outperforms the rule baseline on held-out precision ($P \ge 0.933$) and recall ($R \ge 0.875$) without regressing precision on security anti-patterns (`SECURITY_HARDCODED_SECRET`, `SECURITY_SQL_INJECTION`, `SECURITY_WEAK_CRYPTO`).
   - Inference latency on CPU remains bounded ($p95 \le 50\text{ ms}$ per hunk).
   - Peak memory usage does not exceed container allocations ($< 1\text{ GiB}$).

---

### 1.3 True-Client-IP Edge Validation on Render (SEC-12)

- **Category:** Infrastructure & Security Validation
- **Priority:** High
- **Tracking:** SEC-12

#### Rationale
The authentication rate limiter (`AuthRateLimitFilter`) defends against credential stuffing and brute-force attacks by keying attempts on client IP addresses. In the Render multi-tier deployment, the API service receives incoming HTTP requests from the Next.js runtime proxy over Render's internal private network (`10.0.0.0/8`).

When the immediate peer IP falls within `10.0.0.0/8`, `AuthRateLimitFilter` trusts `AUTH_CLIENT_IP_HEADER` (`True-Client-IP`). Next.js route handlers lack direct TCP socket access and cannot append to `X-Forwarded-For` (E-19), so the web proxy forwards `True-Client-IP` verbatim. If Render's external ingress edge allows arbitrary incoming HTTP headers without stripping or overwriting `True-Client-IP`, an external attacker could forge arbitrary IP addresses to evade rate limits.

#### Acceptance Criteria
1. Deploy a test probe or verify against the live Render staging environment by sending requests with forged `True-Client-IP: 1.2.3.4` from external networks.
2. Inspect server-side request headers received by the API container to determine whether Render's Cloudflare/ingress edge overwrites `True-Client-IP` with the real client IP, strips it, or allows the header to pass untouched.
3. If the header is forgeable through the public edge:
   - Configure a shared ingress secret header (e.g. `X-Proxy-Secret`) generated per environment between the Next.js proxy and API.
   - Update `AuthRateLimitFilter` to accept forwarded IP headers only when the trusted proxy secret matches.
4. Document the validated edge behavior in `docs/security-review.md`.

---

### 1.4 Independent Fixture Adjudication

- **Category:** Evaluation Integrity & Quality Assurance
- **Priority:** Medium
- **Tracking:** Review Benchmark limitations follow-up

#### Rationale
The 21 PR fixtures (13 dev, 8 heldout) in `benchmarks/fixtures/` and their ground-truth labels were authored by the same engineering team that tuned the rule heuristics (`app/fallback_scanner.py` and `app/code_lexer.py`). Although the held-out split was isolated prior to rule freezing to avoid direct over-fitting, single-annotator ground truth retains inherent risks of blind spots, idiosyncratic test cases, and shared assumptions between the test suite and the implementation.

#### Acceptance Criteria
1. The 21 benchmark fixtures and their `expected` / `negatives` YAML files are independently reviewed by at least two external senior software engineers and application security specialists.
2. Reviewers independently annotate all defects and false-positive decoys without prior exposure to the committed ground truth.
3. Inter-annotator agreement (Cohen's Kappa $\kappa$) is calculated for anti-pattern classification and severity ratings.
4. Any labeling disputes or borderline cases (e.g. weak crypto used for content hashing vs. security tokens, magic numbers in timeouts) are reconciled and documented in `docs/review-benchmark.md`.

---

### 1.5 Tree-Sitter AST Localization

- **Category:** Rule Precision & Static Analysis
- **Priority:** Medium
- **Tracking:** Rule engine evolution

#### Rationale
The current rule engine in `apps/ml-worker/app/fallback_scanner.py` utilizes regex patterns augmented by `app/code_lexer.py` to mask comments and string literals. While this achieved an impressive held-out precision of 0.933 (eliminating false positives from comment mentions like `// console.log`), regex heuristics cannot:
- Model syntactic scopes across multi-line statements or nested functions.
- Parse complex expressions (nested template strings, JSX/TSX elements, complex lambda bodies).
- Distinguish whether an identifier in a loop originates from an outer scope or an iterated collection.
- Reason across multi-line AST subtrees without error-prone multi-line regex backtracking.

Migrating the rule engine from lexical regex scanning to concrete syntax tree (CST) queries via Tree-sitter provides robust, incremental parsing across Python, TypeScript, JavaScript, and Java.

#### Acceptance Criteria
1. `tree-sitter` and language grammar packages (`tree-sitter-python`, `tree-sitter-javascript`, `tree-sitter-typescript`, `tree-sitter-java`) integrated into `apps/ml-worker`.
2. Structural rules migrated to Tree-sitter query patterns:
   - `PERFORMANCE_QUADRATIC_LOOP` (detecting nested loops over collections via AST node hierarchy)
   - `RELIABILITY_BROAD_EXCEPTION` (detecting catch blocks lacking re-throw statements)
   - `READABILITY_LONG_METHOD` (accurately counting function body statements rather than line character lengths)
   - `RELIABILITY_MISSING_TIMEOUT` (identifying HTTP call nodes lacking timeout keyword arguments or options properties)
3. Benchmark suite (`benchmarks/run_benchmark.py`) runs with Tree-sitter enabled, achieving equal or higher recall with zero false-positive regressions.
4. Parsing throughput remains sub-millisecond ($p95 \le 3\text{ ms}$ per file) with minimal native memory footprint.

---

### 1.6 Semantic Duplicate Detection (`MAINTAINABILITY_DUPLICATE_CODE`)

- **Category:** Rule Coverage & Anti-Pattern Detection
- **Priority:** Low
- **Tracking:** Taxonomy completeness

#### Rationale
`MAINTAINABILITY_DUPLICATE_CODE` is currently the only anti-pattern in `taxonomy/anti_patterns.yaml` that is marked `trainable: true` but has no corresponding rule in `fallback_scanner.py`. Detecting duplicated code blocks in unified PR diffs is impossible with simple regex without triggering massive false positive rates on short idiomatic phrases, boilerplate declarations, and repetitive imports.

#### Acceptance Criteria
1. Implement a token-based or AST-based clone detector in `apps/ml-worker` that compares added lines within a diff against:
   - Sibling hunks in the same pull request.
   - Existing functions within the modified source files.
2. Normalized token sequences (stripping variable names, comments, and formatting) evaluated with a configurable threshold (e.g. minimum 6 consecutive statements or 40 tokens).
3. Rule produces accurate line ranges and redacted evidence showing both duplicate instances.
4. Evaluated against dedicated duplicate-code fixtures in `benchmarks/fixtures/` with precision $\ge 0.90$.

---

### 1.7 One-Click Suggestion Previews (Interactive Remediation)

- **Category:** Developer Experience & GitHub Integration
- **Priority:** Medium
- **Tracking:** C06 / CON-01 follow-up

#### Rationale
ACRT currently publishes high-quality remediation advice in both the API finding payload (`remediation`, $\le 300$ chars) and the markdown review comment summary posted to GitHub. However, developers must manually open their editor to make the suggested code change. GitHub supports native suggestion markdown blocks:
````markdown
```suggestion
const response = await fetch(url, { signal: AbortSignal.timeout(5000) });
```
````
Providing syntactically valid suggestion blocks in inline PR review comments allows developers to apply fixes directly from GitHub's pull request UI with a single click.

#### Acceptance Criteria
1. For high-confidence ($> 0.90$) rule detections where remediation is deterministic (e.g., adding timeouts to `fetch` or replacing `Math.random()` with `crypto.randomBytes()`), the ML worker generates a concrete code patch string.
2. The GitHub review publisher (`ReviewPublisher.java`) renders these replacement strings within ` ```suggestion ` blocks on inline comments when `repo.settings.inlineComments` is enabled.
3. The web dashboard renders an interactive diff preview comparing original added lines against suggested remediation.
4. Generated code replacements are verified through lexical validation to ensure they do not introduce syntax errors.

---

### 1.8 Render Staging Deployment & Automated Smoke Verification

- **Category:** DevOps & Production Readiness
- **Priority:** High
- **Tracking:** Operational gate before production cutover

#### Rationale
The hardening release addresses critical deployment topology issues:
- Replaced cross-site client architecture with a same-origin Next.js proxy (`API_INTERNAL_URL`).
- Replaced insecure cookie naming with `__Host-` cookies when `COOKIE_SECURE=true`.
- Aligned OAuth callback URLs to `<web origin>/api/auth/callback`.
- Updated all container Dockerfiles to Node 24 alpine base images.

While local unit, integration, and Playwright E2E suites pass, deploying the updated `render.yaml` to an actual Render staging environment is necessary to validate real network routing, TLS certificate handling, public suffix cookie behavior, and external GitHub webhook delivery.

#### Acceptance Criteria
1. Deploy `render.yaml` blueprint to an isolated Render staging environment connecting PostgreSQL, Redis, ML worker, Spring Boot API, and Next.js Web services.
2. Perform automated smoke verification against disposable test repository `tanmay-alpha/demo-repo`:
   - Complete GitHub OAuth sign-in flow via the web proxy and confirm `__Host-accessToken` and `__Host-refreshToken` cookies are set with `Secure; SameSite=Lax`.
   - Connect the repository and verify webhook creation on GitHub.
   - Push a test commit containing known anti-patterns; verify webhook delivery returns 200, outbox claims execute, and the sticky comment is posted with valid findings.
   - Verify `GET /api/diagnostics` reports all components `up` and zero dead-letter queues.
3. Execute the post-deployment gates specified in [Release Checklist](release-checklist.md).
