# CodeLens — Architecture

This document is the authoritative reference for how CodeLens is wired together.
Source of truth: [ENGINEERING_PLAN.md](../ENGINEERING_PLAN.md).

---

## 1. System Architecture Diagram

Copy of the diagram from `ENGINEERING_PLAN.md` Section 4 (deployment view).

```
┌─────────────────────────────────────────────────────────┐
│                    INTERNET                             │
└────────┬───────────────────────────┬────────────────────┘
         │                           │
         ▼                           ▼
┌──────────────────┐      ┌──────────────────────────────┐
│  VERCEL (web)    │      │   RAILWAY PROJECT            │
│  Next.js App     │      │                              │
│  Auto-deploy     │      │  ┌─────────────────────┐     │
│  from GitHub     │ ────▶│  │  Spring Boot API     │     │
│                  │      │  │  Port 8080           │     │
└──────────────────┘      │  │  2 replicas          │     │
                          │  └────────┬────────────┘     │
                          │           │                   │
                          │  ┌────────▼────────────┐     │
                          │  │  PostgreSQL 16       │     │
                          │  │  Railway managed DB  │     │
                          │  └─────────────────────┘     │
                          │                              │
                          │  ┌─────────────────────┐     │
                          │  │  Redis 7             │     │
                          │  │  Railway managed     │     │
                          │  └─────────────────────┘     │
                          └──────────────────────────────┘
                                      │
                                      │ internal HTTP
                                      ▼
                          ┌──────────────────────────────┐
                          │  HUGGINGFACE INFERENCE        │
                          │  ENDPOINTS                   │
                          │  FastAPI + CodeBERT           │
                          │  CPU inference (free tier)    │
                          └──────────────────────────────┘
```

---

## 2. Services

| Service | Path | Language / Runtime | Port | Responsibility |
|---|---|---|---|---|
| `apps/api` | `apps/api` | Java 21 + Spring Boot 3.3 | **8080** | OAuth, webhooks, DB writes, GitHub API calls, orchestration of ML inference, posting review comments, issuing VS Code API keys. |
| `apps/ml-worker` | `apps/ml-worker` | Python 3.11 + FastAPI 0.115 | **8000** | Loads fine-tuned CodeBERT at startup, parses unified diff into hunks, runs sliding-window inference, returns structured findings JSON. No DB / no auth. |
| `apps/web` | `apps/web` | TypeScript + Next.js 15 | **3000** | Dashboard UI: GitHub login, repo list, PR review detail with diff viewer + annotations, quality trend charts, taxonomy browser, settings/API-key copy. |
| `apps/vscode-ext` | `apps/vscode-ext` | TypeScript (VS Code ext API) | — | Reads open file on save, calls `POST /api/scan/file` with API key, renders inline squiggles + hover messages. |
| `github-action/` | `github-action` | Node.js (JavaScript) | — | Triggered on `pull_request`. Fetches diff with `GITHUB_TOKEN`, calls `POST /api/scan/action`, posts findings as a PR check-run annotation. |
| PostgreSQL 16 | (managed by Railway) | SQL | 5432 | Source of truth for users, repositories, pull requests, findings, quality metrics, webhook dedup. |
| Redis 7 | (managed by Railway) | key-value | 6379 | Rate-limit counters + webhook dedup. Session state is NOT stored here (JWT is stateless). |

---

## 3. Data Flow — PR Open → ML Inference → GitHub Comment

Numbered, end-to-end path from a developer opening a pull request to CodeLens posting findings back on the PR.

1. **Developer opens / synchronizes a pull request** on a connected GitHub repo.
2. **GitHub fires a `pull_request` webhook** to `https://<api-host>/api/webhook/github` with headers `X-Hub-Signature-256`, `X-GitHub-Event`, `X-GitHub-Delivery`, and the JSON payload.
3. **Spring Boot `WebhookController` receives the request.** `HmacVerifier` recomputes HMAC-SHA256 using the per-repo secret and compares with `MessageDigest.isEqual` (timing-safe). Rejects forged signatures with `401`.
4. **Dedup check.** Spring Boot looks up `X-GitHub-Delivery` in the `processed_webhooks` table. If present, returns `200 { "status": "duplicate" }` and stops.
5. **Insert dedup row** with the delivery ID, then respond `200 { "status": "accepted" }` immediately. The handler is annotated `@Async` — GitHub gets an instant ACK.
6. **`WebhookService.processAsync()` runs on a worker thread.** It loads the `PullRequest` row (creating one with status `processing` if new) and fetches the unified diff from `https://api.github.com/repos/{owner}/{repo}/pulls/{n}.diff` using the stored OAuth token.
7. **`MlWorkerService` calls `POST http://ml-worker:8000/ml/review`** with `{ diff, language, mode: "diff" }`. The shared `ML_WORKER_SECRET` header is attached for service-to-service auth.
8. **FastAPI ML worker parses the diff** into per-file hunks (`diff_parser.py`), tokenizes each hunk with a 512-token sliding window (`tokenizer_utils.py`), and runs inference with the fine-tuned CodeBERT model (`model.py`).
9. **Per-label sigmoid threshold of 0.5** turns logits into binary category activations. Each positive label becomes a `Finding` with `lineStart`, `lineEnd`, `antiPattern`, `category`, `severity`, `confidence`, `explanation`.
10. **FastAPI responds** with `{ findings: [...], qualityScore, processingTimeMs, windowsProcessed }`.
11. **Spring Boot writes findings to PostgreSQL.** One row per finding in the `findings` table, linked to the `pull_requests` row. The PR's `quality_score` is updated; `quality_metrics` is upserted for today's date.
12. **`GitHubService` posts a review comment** to the PR via `POST /repos/{owner}/{repo}/issues/{n}/comments` (or check-run annotation API for the GitHub Action path). The comment lists each finding with file path, line range, severity emoji, and confidence.
13. **`PullRequest.status` is set to `reviewed`** (or `failed` with `error_message` if any step threw). `quality_metrics` is recomputed for the chart pre-aggregation.
14. **Next.js dashboard reflects the new state** on next fetch — `GET /api/reviews/{prId}` returns the saved findings; `GET /api/metrics/quality-trend` returns the updated time-series point.

End-to-end latency target: **≤ 10 seconds** from webhook receipt to GitHub comment.

---

## 4. Database ERD (text format)

```
┌──────────────────────────────────────────────────────────────────┐
│ users                                                            │
│──────────────────────────────────────────────────────────────────│
│ PK  id                  UUID                                      │
│ UQ  github_id           BIGINT                                    │
│     github_username     VARCHAR(100)                             │
│     avatar_url          TEXT                                      │
│     access_token        TEXT          -- AES-256-GCM encrypted    │
│     refresh_token       TEXT          -- bcrypt-hashed           │
│     api_key_hash        VARCHAR(100)  -- bcrypt of cl_live_*     │
│     api_key_prefix      VARCHAR(10)   -- "cl_live_abc…" display  │
│     created_at, updated_at TIMESTAMPTZ                            │
└──────────────────────────────────────────────────────────────────┘
        │ 1
        │
        │ owns
        ▼ N
┌──────────────────────────────────────────────────────────────────┐
│ repositories                                                     │
│──────────────────────────────────────────────────────────────────│
│ PK  id                  UUID                                      │
│ UQ  github_id           BIGINT                                    │
│     full_name           VARCHAR(255)  -- "tanmay-alpha/MAET"     │
│     description         TEXT                                       │
│     is_private          BOOLEAN                                   │
│ FK  owner_id            UUID  ──▶ users.id   (ON DELETE CASCADE)  │
│     webhook_id          BIGINT                                    │
│     webhook_secret      TEXT  -- AES-256-GCM encrypted           │
│     is_active           BOOLEAN                                   │
│     quality_score       DECIMAL(5,2)                              │
│     created_at, updated_at TIMESTAMPTZ                            │
└──────────────────────────────────────────────────────────────────┘
        │ 1
        │
        │ has many
        ▼ N
┌──────────────────────────────────────────────────────────────────┐
│ pull_requests                                                    │
│──────────────────────────────────────────────────────────────────│
│ PK  id                  UUID                                      │
│ UQ  (repo_id, github_pr_number)                                   │
│     github_pr_number    INT                                       │
│ FK  repo_id             UUID  ──▶ repositories.id  (CASCADE)     │
│     title               TEXT                                      │
│     author_github       VARCHAR(100)                              │
│     head_sha            VARCHAR(40)                               │
│     github_pr_url       TEXT                                      │
│     status              VARCHAR(20) -- pending|processing|        │
│                                       reviewed|failed             │
│     quality_score       DECIMAL(5,2)                              │
│     github_comment_id   BIGINT                                    │
│     error_message       TEXT                                      │
│     reviewed_at         TIMESTAMPTZ                               │
│     created_at          TIMESTAMPTZ DEFAULT NOW()                 │
└──────────────────────────────────────────────────────────────────┘
        │ 1
        │
        │ has many
        ▼ N
┌──────────────────────────────────────────────────────────────────┐
│ findings                                                         │
│──────────────────────────────────────────────────────────────────│
│ PK  id                  UUID                                      │
│ FK  pr_id               UUID  ──▶ pull_requests.id  (CASCADE)    │
│     file_path           TEXT                                      │
│     line_start          INT                                       │
│     line_end            INT                                       │
│     anti_pattern        VARCHAR(80)  -- "PERFORMANCE_N_PLUS_1"    │
│     category            VARCHAR(30)  -- SECURITY|PERFORMANCE|    │
│                                         ARCHITECTURE|RELIABILITY│
│                                         READABILITY|MAINTAINABILITY│
│     severity            VARCHAR(10)  -- critical|major|minor      │
│     confidence          DECIMAL(4,3) -- 0.000–1.000               │
│     explanation         TEXT                                      │
│     code_snippet        TEXT   -- flagged lines, ≤500 chars      │
│     created_at          TIMESTAMPTZ                               │
└──────────────────────────────────────────────────────────────────┘


┌──────────────────────────────────────────────────────────────────┐
│ quality_metrics     (pre-aggregated daily rollups)                │
│──────────────────────────────────────────────────────────────────│
│ PK  id                  UUID                                      │
│ FK  repo_id             UUID  ──▶ repositories.id   (CASCADE)    │
│ UQ  (repo_id, date)                                              │
│     date                DATE                                      │
│     avg_quality         DECIMAL(5,2)                              │
│     prs_reviewed        INT                                       │
│     critical_count      INT                                       │
│     major_count         INT                                       │
│     minor_count         INT                                       │
└──────────────────────────────────────────────────────────────────┘
        ▲ N
        │
        │ rolls up per day
        │
┌──────────────────────────────────────────────────────────────────┐
│ repositories  (1) ──< quality_metrics (N)                         │
└──────────────────────────────────────────────────────────────────┘


┌──────────────────────────────────────────────────────────────────┐
│ processed_webhooks    (dedup table, no FK)                       │
│──────────────────────────────────────────────────────────────────│
│ PK  delivery_id        VARCHAR(100)  -- X-GitHub-Delivery header │
│     processed_at       TIMESTAMPTZ                               │
└──────────────────────────────────────────────────────────────────┘
```

### Relationship summary

| Parent | Child | Cardinality | Cascade on delete |
|---|---|---|---|
| `users` | `repositories` | 1 → N | CASCADE |
| `repositories` | `pull_requests` | 1 → N | CASCADE |
| `pull_requests` | `findings` | 1 → N | CASCADE |
| `repositories` | `quality_metrics` | 1 → N | CASCADE |
| `processed_webhooks` | — | standalone | TTL ≈ 24h |

### Key indexes

- `idx_users_github_id` — OAuth lookup on callback
- `idx_repos_owner_id` — dashboard repo list per user
- `idx_repos_github_id` — webhook → repo resolution
- `idx_prs_repo_id` — PR list per repo
- `idx_prs_status` — queue / dashboard filtering
- `idx_findings_pr_id` — review detail page
- `idx_findings_category` — taxonomy browser
- `idx_metrics_repo_date` — quality-trend chart (DESC for newest-first)