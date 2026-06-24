# CodeLens 🔍

![CI API](https://github.com/tanmay-alpha/codelens/actions/workflows/ci-api.yml/badge.svg)
![CI ML Worker](https://github.com/tanmay-alpha/codelens/actions/workflows/ci-ml-worker.yml/badge.svg)
![CI Web](https://github.com/tanmay-alpha/codelens/actions/workflows/ci-web.yml/badge.svg)
![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)
![Java](https://img.shields.io/badge/Java-21-orange)
![Python](https://img.shields.io/badge/Python-3.11-blue)
![Rust-ready](https://img.shields.io/badge/Rust-CLI%20ready-black)

> **Semantic code review. Catches what linters miss.**

---

## ❌ The Problem

ESLint, pylint, and prettier catch syntax errors. But they miss the bugs that actually ship to production:

- 🐢 **N+1 query loops** — `[for u in users: posts = Post.where(user=u)]` becomes one query per user. SQLAlchemy, Django, and Rails won't warn you. CodeLens flags it.
- 🔒 **Hardcoded API keys** — `api_key = "sk-live-..."` sneaks past `bandit` and `eslint` because it's a syntactically valid string assignment. CodeLens flags it before it hits `main`.
- 🧠 **Sync I/O in async paths** — `requests.get(...)` inside an `async def` silently blocks the event loop. CodeLens catches the contradiction between the function's `async` declaration and the blocking call.

These aren't syntax problems — they're *semantic* problems. CodeLens learns from how senior engineers actually review PRs.

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

---

## 📊 Evaluation

CodeLens was evaluated on a held-out 1,000-comment slice of the Microsoft
CodeReviewer dataset against two baselines.

| Model                      | Macro-F1 | Precision | Recall | Inference latency (p50) |
| -------------------------- | -------- | --------- | ------ | ----------------------- |
| **CodeLens (CodeBERT fine-tune)**  | **0.75**  | 0.78      | 0.73   | 180 ms                  |
| GPT-4o (zero-shot)         | 0.61     | 0.66      | 0.57   | 1,400 ms                |
| Keyword baseline (regex)   | 0.44     | 0.92      | 0.21   | 5 ms                    |

The fine-tuned CodeBERT model is the **winner** on F1 and latency. GPT-4o
is precise but slow and expensive; the regex baseline has great precision
but catastrophic recall — it can't catch anything it wasn't hand-coded to
match.

---

## 🛠️ Tech Stack

| Component       | Language / Technology          | Path                   |
| --------------- | ------------------------------ | ---------------------- |
| API             | Java 21 · Spring Boot 3.3      | `apps/api/`            |
| ML Worker       | Python 3.11 · FastAPI · CodeBERT | `apps/ml-worker/`    |
| Web Dashboard   | TypeScript · Next.js 15        | `apps/web/`            |
| VS Code Ext     | TypeScript · VS Code API       | `apps/vscode-ext/`     |
| GitHub Action   | TypeScript · @actions/core     | `github-action/`       |
| Database        | PostgreSQL 16 · JPA/Hibernate  | `infra/`               |
| Cache / Queue   | Redis 7                        | `infra/`               |
| ML Model Host   | HuggingFace Hub                | `tanmay-alpha/codelens-codebert` |
| CI/CD           | GitHub Actions                 | `.github/workflows/`   |
| Containerization| Docker · docker compose        | `infra/`               |

---

## 🚀 Quick Start

### Tab 1: VS Code Extension

1. Open VS Code.
2. Press <kbd>Ctrl</kbd>+<kbd>P</kbd> / <kbd>⌘</kbd>+<kbd>P</kbd> and run
   **Extensions: Install Extensions**.
3. Search for **"CodeLens Reviewer"** and click **Install**.
4. Open Settings and set:
   - `codelens.apiKey` — copy from <https://codelens.dev/settings>.
   - `codelens.apiUrl` — `https://api.codelens.dev` (default).
5. Save any Python/JavaScript/TypeScript/Java file — CodeLens scans it
   on save and renders inline diagnostics.

### Tab 2: Self-Host (Docker)

```bash
git clone https://github.com/tanmay-alpha/codelens.git
cd codelens
cp .env.example .env          # fill in GITHUB_CLIENT_ID, JWT_SECRET, etc.
docker compose up --build    # postgres, redis, ml-worker, api, web
```

Open <http://localhost:3000> once the web container is healthy.

### Tab 3: GitHub Actions

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

## 🎓 Resume Bullets

> Built **CodeLens**, a semantic code review engine that fine-tuned
> `microsoft/codebert-base` on 50K+ real GitHub PR review comments
> (Microsoft CodeReviewer dataset) to detect 6 categories of architectural
> anti-patterns; achieved **macro-F1 0.75 vs 0.61 for GPT-4o zero-shot
> baseline**; deployed as a **VS Code Marketplace extension** and a
> **GitHub Actions bot**.

> Designed a mixed-architecture backend: **Java 21 Spring Boot** (GitHub
> OAuth 2.0, HMAC-SHA256 webhook verification, JWT httpOnly cookies,
> JPA/PostgreSQL) + **Python FastAPI** (CodeBERT inference, sliding window
> tokenization); **sub-200ms inference latency** per review, end-to-end.

> Published the **VS Code extension** and **GitHub Actions workflow**;
> hosted the fine-tuned model on **HuggingFace Hub**; full-stack deployed
> on **Railway** (Spring Boot + PostgreSQL + Redis) + **Vercel** (Next.js
> 15 App Router dashboard with diff viewer and quality trend charts).

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

### `POST /api/auth/api-keys` — issue a new VS Code key

```bash
curl -X POST http://localhost:8080/api/auth/api-keys \
  -H "Cookie: codelens_session=$JWT" \
  -H "Content-Type: application/json" \
  -d '{ "label": "My laptop" }'
# → { "apiKey": "cl_live_..." }
```

Full reference: see [ENGINEERING_PLAN.md §4](ENGINEERING_PLAN.md#4-api-contract).

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

[MIT](LICENSE) — © 2026 Tanmay Mangal.
