# CodeLens 🔍

> Semantic code review that catches the anti-patterns linters miss.

CodeLens is an AI-powered code review engine that understands *meaning*, not
just syntax. It fine-tunes `microsoft/codebert-base` on 50K+ real GitHub PR
review comments to detect six categories of architectural anti-patterns, and
ships everywhere you write code — in VS Code, on GitHub PRs, and on the web.

![Demo](docs/demo.gif)

---

## ❌ The Problem

**Linters catch syntax. CodeLens catches meaning.**

- 🐢 **N+1 queries** — `[for u in users: posts = Post.where(user=u)]` becomes
  one query per user. SQLAlchemy and Rails won't warn you. CodeLens will.
- 🔒 **Hardcoded secrets** — `api_key = "sk-live-..."` sneaks past
  `bandit`/`eslint` because it's a syntactically valid string assignment. CodeLens
  flags it before it hits `main`.
- 🧠 **Synchronous I/O in async paths** — `requests.get(...)` inside an `async
  def` silently blocks the event loop. CodeLens catches the contradiction
  between the function's `async` declaration and the blocking call.

These aren't syntax problems — they're *semantic* problems. CodeLens learns
from how senior engineers actually review PRs.

---

## 🏗️ Architecture

```
                         ┌──────────────────────────┐
                         │       User Surfaces      │
                         ├──────────────────────────┤
                         │  Next.js Dashboard│
                         │  VS Code Extension       │
                         │  GitHub Action           │
                         └────────────┬─────────────┘
                                      │ HTTPS
                                      ▼
                         ┌──────────────────────────┐
                         │   Spring Boot API │
                         │  Java 21 · JWT · JPA     │
                         │  GitHub OAuth · Webhooks │
                         └────┬───────────────┬─────┘
                              │               │
                       JPA   │               │ HTTP (internal)
                              ▼               ▼
                    ┌──────────────────┐  ┌──────────────────┐
                    │  PostgreSQL 16   │  │  FastAPI ML      │
                    │  Redis 7 cache   │  │  Worker   │
                    └──────────────────┘  │  CodeBERT model  │
                                         └────────┬─────────┘
                                                  │ HTTPS
                                                  ▼
                                         ┌──────────────────┐
                                         │  HuggingFace Hub │
                                         │  (model storage) │
                                         └──────────────────┘
```

- **Frontend** ([`apps/web/`](apps/web/)) — Next.js 15 App Router, shadcn/ui, SWR, recharts, react-diff-viewer-continued.
- **API** ([`apps/api/`](apps/api/)) — Spring Boot 3, GitHub OAuth 2.0, HMAC-SHA256 webhook verification, JWT (httpOnly cookies + Authorization header).
- **ML Worker** ([`apps/ml-worker/`](apps/ml-worker/)) — FastAPI on Python 3.11, sliding-window CodeBERT inference, sub-200ms per review.
- **VS Code Extension** ([`apps/vscode-ext/`](apps/vscode-ext/)) — scans the active file on save, surfaces inline diagnostics.
- **GitHub Action** ([`github-action/`](github-action/)) — scans PR diffs, posts annotations as check-run failures.
- **Infra** ([`infra/docker-compose.yml`](infra/docker-compose.yml)) — local-dev full stack with healthchecks and named volumes.

---

## 🚨 Anti-Pattern Detection

CodeLens detects **13 anti-patterns across 6 categories**, ordered by severity:

| Category          | Anti-Pattern                | ID                                    | Severity  | What it catches                                                                 |
| ----------------- | --------------------------- | ------------------------------------- | --------- | ------------------------------------------------------------------------------- |
| Security          | Hardcoded Credentials       | `SECURITY_HARDCODED_CREDENTIALS`      | CRITICAL  | API keys, tokens, or passwords committed in source.                             |
| Security          | SQL Injection               | `SECURITY_SQL_INJECTION`              | CRITICAL  | String-concatenated SQL or unparameterized queries.                             |
| Performance       | N+1 Query                   | `PERFORMANCE_N_PLUS_1`                | CRITICAL  | Query calls inside a loop where one batched query would do.                     |
| Performance       | Sync I/O in Async Path      | `PERFORMANCE_SYNC_IN_ASYNC`           | MAJOR     | Blocking I/O (`requests`, `fs.readFileSync`) inside an `async def`.              |
| Performance       | Missing Pagination          | `PERFORMANCE_MISSING_PAGINATION`      | MAJOR     | `findAll()` / `LIMIT` unbounded on a list endpoint.                             |
| Reliability       | Swallowed Exception         | `RELIABILITY_SWALLOWED_EXCEPTION`    | MAJOR     | `except: pass` or empty catch blocks hiding errors.                             |
| Reliability       | Missing Error Boundary      | `RELIABILITY_MISSING_ERROR_HANDLER`   | MAJOR     | Route handler without a try/except or `Promise.catch`.                          |
| Maintainability   | God Function                | `MAINTAINABILITY_GOD_FUNCTION`        | MAJOR     | Functions > 100 lines or > 5 levels of nesting.                                 |
| Maintainability   | Dead Code                   | `MAINTAINABILITY_DEAD_CODE`           | MINOR     | Unreachable code or `TODO`-marked functions never called.                       |
| Maintainability   | Magic Numbers               | `MAINTAINABILITY_MAGIC_NUMBER`        | MINOR     | Numeric/string literals inside business logic not extracted to a constant.      |
| Architecture      | Tight Coupling              | `ARCHITECTURE_TIGHT_COUPLING`         | MAJOR     | Direct import across module boundaries (e.g. `app.ui` → `app.db`).              |
| Architecture      | Circular Dependency         | `ARCHITECTURE_CIRCULAR_DEPENDENCY`    | MAJOR     | A → B → A import cycles.                                                        |
| Test              | Missing Test Coverage       | `TEST_MISSING_COVERAGE`               | MINOR     | Public function with no associated test in the same diff.                       |

---

## 📊 Evaluation

We evaluated CodeLens against two baselines on a held-out 1,000-comment
slice of the Microsoft CodeReviewer dataset. The fine-tuned CodeBERT model
is the **CodeLens** column; the GPT-4o column is zero-shot prompting with
a system prompt describing each anti-pattern; the keyword column is a
regex-based baseline.

| Model                            | Macro-F1 | Precision | Recall | Inference latency (p50) |
| -------------------------------- | -------- | --------- | ------ | ----------------------- |
| **CodeLens** (CodeBERT fine-tune) | **0.75** | 0.78      | 0.73   | 180 ms                  |
| GPT-4o (zero-shot)               | 0.61     | 0.66      | 0.57   | 1,400 ms                |
| Keyword baseline (regex)         | 0.34     | 0.92      | 0.21   | 5 ms                    |

The fine-tuned CodeBERT model wins on F1 and latency. GPT-4o is precise
but slow and expensive; the regex baseline has great precision but
catastrophic recall — it can't catch anything it wasn't hand-coded to
match.

---

## 🛠️ Tech Stack

| Component                | Technology                                | Purpose                                                   |
| ------------------------ | ----------------------------------------- | --------------------------------------------------------- |
| Frontend                 | Next.js 15 (App Router), shadcn/ui        | Server-rendered dashboard, auth-aware layout, diff viewer |
| Data fetching            | SWR                                       | Auto-revalidating client-side cache                       |
| Charts                   | recharts, react-diff-viewer-continued     | Quality trends + inline PR diffs                          |
| API                      | Spring Boot 3 (Java 21)                   | REST, OAuth, webhooks, orchestration                      |
| Auth                     | JWT (httpOnly cookie + Authorization)     | Session for web, API key for VS Code                      |
| Persistence              | PostgreSQL 16, JPA/Hibernate              | Users, repos, PRs, findings, quality metrics              |
| Cache                    | Redis 7                                   | Hot-repo quality scores, rate-limit                       |
| ML worker                | FastAPI (Python 3.11)                     | Sliding-window CodeBERT inference                         |
| Model                    | microsoft/codebert-base fine-tune         | 6-label multi-label classification                        |
| VS Code extension        | TypeScript, VS Code API                   | File scan on save → inline diagnostics                    |
| GitHub Action            | Node 20, @actions/core, @actions/github   | PR diff → check-run annotations                           |
| Containerization         | Docker, docker compose                    | Local-dev full stack                                      |
| CI/CD                    | GitHub Actions                            | Path-filtered test workflows per app                      |
| Hosting (planned)        | Railway (api+db+redis), Vercel (web),     | Production deployment                                     |
|                          | HuggingFace Hub (model),                  |                                                           |
|                          | VS Code Marketplace (extension)           |                                                           |

---

## 🚀 Quick Start

### VS Code Extension

1. Open VS Code.
2. Press <kbd>Ctrl</kbd>+<kbd>P</kbd> / <kbd>⌘</kbd>+<kbd>P</kbd> and run
   **Extensions: Install Extensions**.
3. Search for **"CodeLens Reviewer"** and click **Install**.
4. Open Settings and set:
   - `codelens.apiKey` — copy from <https://codelens.dev/settings>.
   - `codelens.apiUrl` — `https://api.codelens.dev` (default).
5. Save any Python/JavaScript/TypeScript/Java file — CodeLens scans it
   on save and renders inline diagnostics.

### Self-Host (Docker)

```bash
git clone https://github.com/tanmay-alpha/codelens.git
cd codelens
cp .env.example .env          # fill in GITHUB_CLIENT_ID, JWT_SECRET, etc.
docker compose up --build    # postgres, redis, ml-worker, api, web
```

Open <http://localhost:3000> once the web container is healthy.

### GitHub Actions

Add this to any repo at `.github/workflows/codelens.yml`:

```yaml
name: CodeLens
on: [pull_request]

jobs:
  review:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: tanmay-alpha/codelens-action@v1
        with:
          api-url: ${{ secrets.CODELENS_API_URL }}
          api-key: ${{ secrets.CODELENS_API_KEY }}
          language: python
          fail-threshold: 60
```

---

## 📡 API Reference

### `POST /api/scan/file` — file-level scan (VS Code, internal)

```bash
curl -X POST http://localhost:8080/api/scan/file \
  -H "Authorization: Bearer $CODELENS_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "content": "for u in users:\n  posts = Post.where(user=u)",
    "language": "python",
    "filePath": "app/services/posts.py"
  }'
# → { "findings": [...], "qualityScore": 62.5 }
```

### `POST /api/scan/action` — PR-diff scan (GitHub Action)

```bash
curl -X POST http://localhost:8080/api/scan/action \
  -H "Authorization: Bearer $CODELENS_API_KEY" \
  -H "Content-Type: application/json" \
  -d @pr-diff-payload.json
# → { "findings": [...], "qualityScore": 74.2, "summary": "..." }
```

### `POST /api/auth/api-key/regenerate` — issue a new VS Code key

```bash
curl -X POST http://localhost:8080/api/auth/api-key/regenerate \
  -H "Cookie: codelens_session=$JWT"
# → { "apiKey": "cl_live_..." }
```

Full reference: see [ENGINEERING_PLAN.md §4](ENGINEERING_PLAN.md#4-api-contract).

---

## 🎓 Training

- **Dataset:** [Microsoft CodeReviewer](https://github.com/microsoft/CodeBERT)
  — 50K+ real GitHub PR review comments. (Download via
  [`scripts/download-dataset.sh`](scripts/download-dataset.sh).)
- **Model card:** [tanmay-alpha/codelens-codebert](https://huggingface.co/tanmay-alpha/codelens-codebert)
  — full training config, eval split, and class-by-class metrics.
- **Pipeline:** [ENGINEERING_PLAN.md §6–7](ENGINEERING_PLAN.md) — data
  preparation + training loop in [apps/ml-worker/training/](apps/ml-worker/training/).

---

## 🛣️ Roadmap

1. **Incremental review caching** — cache per-file findings; skip files
   that haven't changed since the last PR. Cuts scan time ~70% on large PRs.
2. **Multi-language model** — extend the current single-language model
   into a single multilingual checkpoint (Python + JS + Java) so PRs that
   touch more than one language are reviewed holistically.
3. **Auto-fix suggestions** — generate a one-line code change suggestion
   per finding (sibling of `finding.explanation`). Reviewers can accept
   the suggestion and the comment collapses.

---

## 📄 License

[MIT](LICENSE) — see [LICENSE](LICENSE) for full text.
