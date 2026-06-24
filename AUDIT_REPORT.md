# CodeLens — Project Audit Report

**Generated:** 2026-06-24
**Repo:** `C:\Users\TANMAY\OneDrive\Desktop\codelens`
**Remote:** `https://github.com/tanmay-alpha/codelens.git`
**Total commits:** 119
**Last commit:** `0673351` — 2026-06-24 00:30:45 +0530
**Working tree:** clean
**Branch:** `main` (up to date with `origin/main`)

This is a read-only audit. No code was changed. Every item is marked ✅ DONE, ❌ MISSING, or 🟡 PARTIAL with a file path and one-line explanation.

---

## Section 1 — Folder Structure

| # | Item | Status | Detail |
|---|------|--------|--------|
| 1 | `apps/api/src/main/java/com/codelens/` | ✅ DONE | 67 .java files across controller/service/entity/security/config/dto/repository/exception/webhook packages |
| 2 | `apps/api/src/main/resources/` | ✅ DONE | application.yml, application-dev.yml, db/migration/ (V1, V2, V3) and db/migration/h2/ (V1, V2, V3) |
| 3 | `apps/api/src/test/java/com/codelens/` | ✅ DONE | 8 test files |
| 4 | `apps/ml-worker/app/` | ✅ DONE | main.py, model.py, diff_parser.py, tokenizer_utils.py, config.py, schemas.py |
| 5 | `apps/ml-worker/tests/` | ✅ DONE | test_api.py, test_diff_parser.py, test_tokenizer_utils.py, __init__.py |
| 6 | `apps/ml-worker/training/` | ✅ DONE | dataset.py, label_mapper.py, train.py, evaluate.py, verify_sample.py, split.py, README.md |
| 7 | `apps/web/src/` | 🟡 PARTIAL | Files exist under `apps/web/src/`, not a bare `apps/web/src/`. Contains app/, components/, lib/ |
| 8 | `apps/web/components/` | ❌ MISSING | Lives at `apps/web/src/components/` instead of `apps/web/components/` |
| 9 | `apps/vscode-ext/src/` | ✅ DONE | extension.ts, reviewer.ts, config.ts |
| 10 | `github-action/` | ✅ DONE | action.yml, index.js, package.json (node_modules present) |
| 11 | `infra/` | ✅ DONE | docker-compose.yml, nginx/ (empty), README.md |
| 12 | `docs/` | 🟡 PARTIAL | Directory exists, but only contains demo.gif.placeholder.md |
| 13 | Root `README.md` | ✅ DONE | 253 lines |
| 14 | Root `RESUME.md` | ✅ DONE | 66 lines |
| 15 | Root `AUDIT.md` | ✅ DONE | 425 lines (from earlier session) |
| 16 | Root `CONTEXT.md` | ✅ DONE | 157 lines |
| 17 | Root `ENGINEERING_PLAN.md.md` | ✅ DONE | 1452 lines |

---

## Section 2 — Backend API (apps/api/)

| # | Item | Status | Detail |
|---|------|--------|--------|
| 18 | .java files in apps/api/src/main/java/ | 67 | Full count |
| 19 | Test files in apps/api/src/test/java/ | 8 | controller (4) + security (2) + service (2) |
| 20 | Controllers | 7 | ApiKeyController, AuthController, MetricsController, RepoController, ReviewController, ScanController, WebhookController |
| 21 | Services | 7 | ApiKeyService, GitHubService, MlWorkerService, RepoService, ReviewService, UserService, WebhookService |
| 22 | Entities | 7 | ApiKey, Finding, ProcessedWebhook, PullRequestEntity, QualityMetric, Repository, User |
| 23 | Security classes | 4 | ApiKeyAuthFilter, EncryptionService, JwtAuthFilter, JwtService |
| 24 | Config classes | 5 | AppConfig, AsyncConfig, GitHubConfig, JwtConfig, SecurityConfig |
| 25 | pom.xml Spring Boot version | ✅ DONE | 3.3.4 (spring-boot-starter-parent line 11) |
| 26 | Dockerfile | ✅ DONE | apps/api/Dockerfile |
| 27 | application.yml with app.* properties | ✅ DONE | 38 app.* keys, `app:` block at line 23 |
| 28 | application-dev.yml | ✅ DONE | src/main/resources/application-dev.yml |
| 29 | application-test.yml | ✅ DONE | src/test/resources/application-test.yml (H2 in PostgreSQL mode) |
| 30 | V1__initial_schema.sql | ✅ DONE | db/migration/V1__initial_schema.sql |
| 31 | db/migration/h2/V1__initial_schema.sql | ✅ DONE | H2-compatible variant present |
| 32 | V3__api_keys.sql | ✅ DONE | db/migration/V3__api_keys.sql |
| 33 | Total @Test methods | 60 | RepoControllerTest=9, ReviewControllerTest=3, ScanControllerTest=7, WebhookControllerTest=5, ApiKeyAuthFilterTest=9, JwtServiceTest=4, MlWorkerServiceTest=10, ReviewServiceTest=13 |
| 34 | @SpringBootTest | 1 | RepoControllerTest |
| 35 | @WebMvcTest | 3 | ReviewControllerTest, ScanControllerTest, WebhookControllerTest |

---

## Section 3 — ML Worker (apps/ml-worker/)

| # | Item | Status | Detail |
|---|------|--------|--------|
| 36 | app/main.py with /ml/review and /ml/health | ✅ DONE | Both endpoints present; verify_secret middleware via hmac.compare_digest |
| 37 | app/model.py | ✅ DONE | |
| 38 | app/diff_parser.py | ✅ DONE | |
| 39 | app/tokenizer_utils.py | ✅ DONE | |
| 40 | app/config.py | ✅ DONE | |
| 41 | app/schemas.py | ✅ DONE | ReviewRequest with diff max_length=200_000 |
| 42 | requirements.txt pinned versions | ✅ DONE | fastapi==0.115.0, uvicorn[standard]==0.30.6, torch==2.3.0, transformers==4.41.0, datasets==2.19.0 |
| 43 | Dockerfile | ✅ DONE | apps/ml-worker/Dockerfile |
| 44 | Test files in tests/ | 3 | + __init__.py |
| 45 | Total test functions | 25 | |
| 46 | FakeTokenizer (no model load) | ✅ DONE | class FakeTokenizer in tests/test_tokenizer_utils.py:29 |
| 47 | tests/conftest.py | ❌ MISSING | |
| 48 | training/dataset.py | ✅ DONE | |
| 49 | training/label_mapper.py | ✅ DONE | |
| 50 | training/train.py | ✅ DONE | |
| 51 | training/evaluate.py | ✅ DONE | |
| 52 | training/verify_sample.py | ✅ DONE | |
| 53 | training/split.py | ✅ DONE | |
| 54 | training/README.md | ✅ DONE | |

---

## Section 4 — CI/CD

| # | Item | Status | Detail |
|---|------|--------|--------|
| 55 | .github/workflows/ files | 3 | ci-api.yml, ci-ml-worker.yml, ci-web.yml |
| 56 | ci-api.yml | ✅ DONE | `mvn test` on apps/api changes; H2 via `@ActiveProfiles("test")` (no SPRING_PROFILES_ACTIVE env var); Postgres service container preserved for ad-hoc runs |
| 57 | ci-ml-worker.yml | ✅ DONE | `pytest -m "not slow"` (skips model-load tests) |
| 58 | ci-web.yml | ✅ DONE | Next.js typecheck + vitest |
| 59 | deploy-prod.yml | ❌ MISSING | Plan §12 listed 4 workflows; only 3 exist |

---

## Section 5 — Infrastructure

| # | Item | Status | Detail |
|---|------|--------|--------|
| 60 | infra/docker-compose.yml services | ✅ DONE | postgres, redis, ml-worker, api, web (5 services) |
| 61 | infra/docker-compose.dev.yml | ❌ MISSING | |
| 62 | infra/nginx/ config files | ❌ MISSING | Directory exists but is empty |
| 63 | Other files in infra/ | ✅ DONE | infra/README.md |

---

## Section 6 — GitHub Action Bot

| # | Item | Status | Detail |
|---|------|--------|--------|
| 64 | github-action/action.yml | ✅ DONE | name "CodeLens Code Review"; inputs: api-url, api-key, language, fail-threshold; outputs: quality-score, findings-count, critical-count; runs via node20 index.js |
| 65 | github-action/index.js | ✅ DONE | |
| 66 | github-action/package.json | ✅ DONE | |
| 67 | github-action/README.md | ❌ MISSING | |

---

## Section 7 — Frontend Dashboard (apps/web/)

| # | Item | Status | Detail |
|---|------|--------|--------|
| 68 | package.json dependencies | ✅ DONE | next 15.1.6, react ^19.0.0, @radix-ui/react-separator, @radix-ui/react-slot, class-variance-authority, clsx, lucide-react, react-diff-viewer-continued, react-dom |
| 69 | next.config | ✅ DONE | apps/web/next.config.mjs |
| 70 | tsconfig.json | ✅ DONE | |
| 71 | tailwind.config | ✅ DONE | tailwind.config.ts |
| 72 | app/ with page files | ✅ DONE | app/page.tsx, app/layout.tsx + dashboard/, pr/[prId]/, repo/[repoId]/, settings/, taxonomy/ |
| 73 | components/ with .tsx files | 🟡 PARTIAL | Lives at apps/web/src/components/ (8 feature .tsx + 6 ui .tsx), not apps/web/components/ |
| 74 | lib/ with API client | ✅ DONE | apps/web/src/lib/api.ts + types.ts + utils.ts |
| 75 | Total .tsx files | 23 | |

---

## Section 8 — VS Code Extension (apps/vscode-ext/)

| # | Item | Status | Detail |
|---|------|--------|--------|
| 76 | apps/vscode-ext/package.json | ✅ DONE | |
| 77 | apps/vscode-ext/src/extension.ts | ✅ DONE | |
| 78 | apps/vscode-ext/README.md | ❌ MISSING | |
| 79 | Total source files in src/ | 3 | extension.ts, reviewer.ts, config.ts |

---

## Section 9 — Documentation

| # | Item | Status | Detail |
|---|------|--------|--------|
| 80 | docs/ files | 🟡 PARTIAL | Only demo.gif.placeholder.md |
| 81 | Root README.md | ✅ DONE | 253 lines |
| 82 | Root RESUME.md | ✅ DONE | 66 lines |
| 83 | AUDIT.md | ✅ DONE | 425 lines |
| 84 | DOCS_DRAFT.md | ✅ DONE | 6715 bytes |

---

## Section 10 — Git State

| # | Item | Status | Detail |
|---|------|--------|--------|
| 85 | Last 10 commits | ✅ DONE | (see below) |
| 86 | Working tree | ✅ DONE | clean |
| 87 | Branches | 17 | 1 main + 14 feat/*/fix/docs + 2 remotes |
| 88 | Remote URL | ✅ DONE | https://github.com/tanmay-alpha/codelens.git |
| 89 | Total commits | 119 | |
| 90 | Last commit time | ✅ DONE | Wed Jun 24 00:30:45 2026 +0530 |

**Last 10 commits:**
```
0673351 docs: add audit report and update session log with ci-api fix history
9f08ac8 fix(ci): ci-api workflow env vars were overriding the test profile's H2 config
24481b0 fix(api): make ci-api RepoControllerTest pass — H2 migrations, schema/principal fixes
111997c fix: ci-api compile errors and ci-ml-worker test mismatches
f10155f Merge pull request #14 from tanmay-alpha/feat/issue-20-github-action-deploy
ff5abce docs: mark issue #20 complete — all 20 issues done
e820e9c docs: add resume bullets and project writeup
b1191d4 docs: add comprehensive README with architecture and evaluation table
96f1bf3 ci: add Spring Boot, FastAPI, and Next.js test workflows
e86134b chore(infra): add complete docker-compose with all services
```

---

## Section 11 — Spec Compliance Spot Checks

| # | Item | Status | Detail |
|---|------|--------|--------|
| 91 | EncryptionService.java — AES-256-GCM, 12-byte IV, 128-bit tag | ✅ DONE | `AES/GCM/NoPadding`, IV_LEN=12, TAG_BITS=128, 32-byte key enforced |
| 92 | ApiKeyAuthFilter.java — Redis rate limit, 60 req/min | ✅ DONE | StringRedisTemplate, `${app.ratelimit.api-key.requests-per-minute:60}`, key prefix `ratelimit:apikey:{userId}` |
| 93 | main.py — hmac.compare_digest for secret | ✅ DONE | line 76 in verify_secret middleware |
| 94 | schemas.py — diff max_length=200_000 | ✅ DONE | `Field(..., min_length=1, max_length=200_000)` |
| 95 | WebhookService.processAsync — real wiring | ✅ DONE | @Async("taskExecutor") → reviewService.orchestrateReview → reviewService.formatGithubComment → githubService.postPrComment |
| 96 | SecurityConfig — CSRF disabled, JWT + API key filters | ✅ DONE | `.csrf(csrf -> csrf.disable())`; both filters added before UsernamePasswordAuthenticationFilter |

---

## Section 12 — Known Gaps

| # | Item | Status | Detail |
|---|------|--------|--------|
| 97 | Rate limit on /api/auth/* (Plan §8) | ❌ MISSING | Only ApiKeyAuthFilter has 60 req/min via Redis. /api/auth/github, /api/auth/callback, /api/auth/refresh are in permitAll with no rate limiter |
| 98 | docker-compose.yml (Plan §13) | ✅ DONE | infra/docker-compose.yml with 5 services |
| 99 | deploy-prod.yml (Plan §12) | ❌ MISSING | Only ci-api, ci-ml-worker, ci-web exist |
| 100 | WebhookService.processAsync end-to-end (Issue #13) | ✅ DONE | Full chain wired: orchestrateReview → formatGithubComment → postPrComment |

---

## Summary of Missing Items

Concrete gaps to finish the project:

1. **Rate limit on /api/auth/** endpoints (Plan §8)
2. **deploy-prod.yml** GitHub Actions workflow (Plan §12)
3. **infra/docker-compose.dev.yml** (Plan §13)
4. **infra/nginx/** config files (empty directory)
5. **tests/conftest.py** for ml-worker
6. **apps/vscode-ext/README.md**
7. **github-action/README.md**
8. **apps/web/components/** vs **apps/web/src/components/** path mismatch
9. **docs/** only has placeholder; needs architecture/eval/README content
10. **apps/vscode-ext/** has minimal source (3 files, no separate commands/tests)
