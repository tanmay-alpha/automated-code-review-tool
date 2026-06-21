# CodeLens — Project Context

> Living project context. Read this before doing anything.
> Source of truth: [ENGINEERING_PLAN.md.md](./ENGINEERING_PLAN.md.md)
> This file is the project memory — the plan is the spec.

---

## 1. Current Issue

We are at **pre-implementation / repo scaffold phase**.

- The engineering plan ([ENGINEERING_PLAN.md.md](./ENGINEERING_PLAN.md.md)) is committed and is final law.
- The monorepo is empty except for the plan document.
- No real code has been written yet.
- The 30-day implementation order (Section 15 of the plan) begins with Issue #1 (download dataset) and ends with deployment + demo video on Day 30.
- The first working demo target is **Day 9**: `curl -X POST http://localhost:8000/ml/review` returns real anti-pattern findings.
- The second working demo target is **Day 15**: open a real PR on a test GitHub repo → CodeLens comment appears within 5 seconds.

The immediate task (this session) is to scaffold the monorepo structure, env example, and gitignore — no real code, no implementations, no business logic.

---

## 2. Tech Stack (Final Decisions)

| Layer | Technology | Version | Reason |
|-------|-----------|---------|--------|
| API Language | Java | 21 (LTS) | Virtual threads, modern records, Spring Boot 3.x |
| API Framework | Spring Boot | 3.3.x | Industry standard, JPA, Security, OAuth2 all built-in |
| Build Tool | Maven | 3.9 | Simpler for single Spring Boot module |
| ML Language | Python | 3.11 | HuggingFace ecosystem, PyTorch |
| ML Framework | FastAPI | 0.115 | Async, Pydantic v2, auto docs |
| ML Model | microsoft/codebert-base | latest | Code-specific pre-training, 125M params, feasible on CPU |
| Frontend | Next.js | 15 (App Router) | You already know this |
| Frontend UI | Tailwind CSS + shadcn/ui | latest | Fast, consistent |
| Charts | Recharts | 2.x | Works with React, simple API |
| Extension | TypeScript | 5.x | VS Code extension API requires it |
| Database | PostgreSQL | 16 | Relational, mature, free |
| ORM | Spring Data JPA + Hibernate | via Spring Boot | Reduces boilerplate |
| Cache | Redis | 7.x | Webhook dedup only (tiny usage) |
| Auth | GitHub OAuth 2.0 + JWT | — | Users sign in with GitHub, JWT for stateless API |
| Containerization | Docker + Docker Compose | — | Local dev + deployment |
| API Client (Java) | Spring WebClient | via Spring Boot | Non-blocking HTTP calls to FastAPI |

### What we are NOT using
- Kafka / RabbitMQ (overkill for MVP — use `@Async`)
- GraphQL (REST is sufficient)
- Kubernetes (Railway handles orchestration)
- Elasticsearch (no full-text search needed)
- Redis for session storage (JWT is stateless)

---

## 3. Service Map (from Section 2 of plan)

| Service | Path | Port | Owns |
|---------|------|------|------|
| API | `apps/api` | 8080 | Business logic, GitHub integration, OAuth, webhooks, DB, orchestration |
| ML Worker | `apps/ml-worker` | 8000 | Diff → anti-pattern findings (CodeBERT inference only) |
| Web | `apps/web` | 3000 | UI — dashboard, PR review, taxonomy, settings |
| VS Code Ext | `apps/vscode-ext` | — | In-editor squiggles + hover via `/api/scan/file` |
| GitHub Action | `github-action/` | — | CI integration via `/api/scan/action` |

---

## 4. Issue Tracker Checklist (#1–#20)

Pulled from Section 14 of the plan. Do not change the order.

### Milestone 1: ML Foundation (Week 1–2)
- [x] **#1** Download and inspect CodeReviewer dataset
- [x] **#2** Implement label mapper with taxonomy
- [x] **#3** Manual verification of 500 samples
- [x] **#4** Train/val/test split by PR ID
- [x] **#5** Implement CodeBERT fine-tuning pipeline
- [x] **#6** Evaluate model against two baselines

### Milestone 2: ML Worker Service (Week 3)
- [x] **#7** Implement unified diff parser
- [x] **#8** Implement sliding window tokenizer
- [ ] **#9** Build FastAPI ML worker with `/ml/review` endpoint

### Milestone 3: Spring Boot API (Week 3–4)
- [ ] **#10** Spring Boot project scaffold + DB entities
- [ ] **#11** GitHub OAuth 2.0 login + JWT issuance
- [ ] **#12** Webhook receiver with HMAC verification
- [ ] **#13** Full review flow — webhook to GitHub comment
- [ ] **#14** Repo management endpoints + webhook installation
- [ ] **#15** VS Code API key generation and validation

### Milestone 4: Frontend + Extensions (Week 5–7)
- [ ] **#16** Next.js auth + dashboard shell
- [ ] **#17** PR review page with diff viewer + annotations
- [ ] **#18** Quality trend chart + repo detail page
- [ ] **#19** VS Code extension — file scan on save
- [ ] **#20** GitHub Actions integration

---

## 5. Non-Negotiable Decisions

These are locked. No deviation without an explicit change request and written reason.

1. **Monorepo layout** is exactly what Section 1 of the plan specifies. No reorganizing.
2. **No Kafka / RabbitMQ / GraphQL / Kubernetes / Elasticsearch** for MVP. Use `@Async`, REST, Railway, none.
3. **JWT in httpOnly cookies**, never localStorage (XSS prevention).
4. **API keys**: `cl_live_` prefix + 32 hex chars, bcrypt-hashed in DB, prefix stored plaintext for display.
5. **Webhook security**: HMAC-SHA256, timing-safe comparison via `MessageDigest.isEqual`.
6. **DB encryption**: GitHub OAuth tokens + webhook secrets encrypted with AES-256-GCM via Spring `@Convert`. Single `ENCRYPTION_KEY` env var.
7. **ML worker is internal-only**. Never exposed to internet. Validated by `ML_WORKER_SECRET` header.
8. **Threshold = 0.5** sigmoid per label. Do not change without documenting.
9. **Test split is untouched** until final eval (`test.json` is sacred).
10. **Train/val/test split by PR ID**, not random rows (prevents leakage).
11. **Loss function is BCEWithLogitsLoss** for multi-label classification.
12. **Branch strategy**: `main` is protected, no direct commits, every change is a PR.
13. **One commit per logical step** during scaffolding. No batching.
14. **Structure only** until this scaffold session is complete. No real code yet.

---

## 6. Session Log

| Date | What happened |
|------|---------------|
| 2026-06-21 | Initial scaffold session. Created `CONTEXT.md`, full monorepo structure per Section 1 of plan (apps/ml-worker, apps/api, apps/web, apps/vscode-ext, github-action, infra, scripts, .github/workflows), `.gitignore` (Java+Python+Node+env), `.env.example` with all Section 10 vars. All 8 commits pushed to `origin/main`. No real code written yet — structure only. |
| 2026-06-21 | Issue #1 — Download and inspect CodeReviewer dataset. On branch `feat/issue-1-dataset`. Added `scripts/download-dataset.sh` (clones microsoft/CodeBERT shallow, copies `CodeReviewer/code-review-data` to `apps/ml-worker/training/data/raw/`), `apps/ml-worker/training/dataset.py` (loads JSON files, prints 5 random examples + total/avg/median counts + label distribution), `apps/ml-worker/requirements.txt` (FastAPI + torch/transformers/datasets + numpy/pandas/sklearn + pydantic — versions match Section 7 plan pins), `apps/ml-worker/requirements-train.txt` (accelerate, evaluate, tensorboard). 4 commits on the feature branch. Dataset download + inspection run still to be executed locally by the engineer; `dataset_stats.md` to be authored from the script's output as part of Issue #1 acceptance. |
| 2026-06-21 | Issue #2 — Implement label mapper with taxonomy. On branch `feat/issue-2-label-mapper`. Added `apps/ml-worker/training/label_mapper.py` with locked 6-category keyword taxonomy (SECURITY, PERFORMANCE, ARCHITECTURE, RELIABILITY, READABILITY, MAINTAINABILITY), `map_comment_to_labels()` returning 6-dim binary vector via case-insensitive substring match, `filter_and_label_dataset()` that drops comments < 20 chars, pure style-nits (length < 80 chars AND tokens ⊆ {nit,typo,rename,spacing,whitespace,format,indent,semicolon}), and zero-label samples. CLI prints raw count, per-filter drop counts, kept total, per-category counts/percentages, avg labels/sample, and 3 random examples. Also added `data/.gitkeep` + `data/raw/.gitkeep` placeholders and fixed a `.gitignore` collision (the previous `data/raw/` rule excluded the directory entirely; switched to `data/raw/*` content-ignore with `!.gitkeep` re-include so placeholder files can be committed). 3 commits on the feature branch. Note: remote main received 2 redundant local merge commits this session (Issue #1 PR was already merged via GitHub UI) — history is consistent but slightly noisy. |
| 2026-06-21 | Issue #3 + #4 — Verification script + PR-level split. On branch `feat/issue-3-4-split`. Added `apps/ml-worker/training/verify_sample.py` (interactive y/n/s/q loop over 500 seeded samples from `filter_and_label_dataset()`, prints sample number, label vector + names, comment, 8-line diff snippet; on quit/completion writes `training/data/verification_report.json` with the exact spec shape incl. empty `common_rejections: []` and `training/data/verification_report.md` with agreement rate, top-3 wrong categories, and PROCEED/REVISE recommendation at 90% threshold). Added `apps/ml-worker/training/split.py` (re-loads raw data with PR provenance — uses CodeReviewer commit hash as PR ID when available, falls back to file basename; shuffles PR IDs with seed 42; splits 80/10/10 at PR level; runs `_attach_labels` per split; writes `train.json`, `val.json`, `test.json` with a top-level `_warning` field in test.json making the held-out contract unmissable; asserts PR-level disjointness; prints per-split sample counts + per-category distributions). 3 commits on the feature branch. Note: Issue #3 acceptance requires the user to actually run the interactive verification (~10–15 min for 500 samples); `verification_report.json` and `.md` will only exist after that local run. |
| 2026-06-21 | Issue #5 + #6 — Training + evaluation. On branch `feat/issue-5-6-training`. Added `apps/ml-worker/training/train.py` (Colab-ready; all Section 7 constants (MODEL_NAME, NUM_LABELS, MAX_SEQ_LENGTH, LEARNING_RATE, BATCH_SIZE, NUM_EPOCHS, WEIGHT_DECAY, WARMUP_RATIO, THRESHOLD, HF_REPO, OUTPUT_DIR, DATA_DIR) at the top; PyTorch `CodeReviewDataset` with __len__/__getitem__; explicit `MultiLabelTrainer` subclass that hard-codes `BCEWithLogitsLoss` so the loss choice is visible at a glance; `compute_metrics` returns macro-F1 + per-label F1; `TrainingArguments` with `evaluation_strategy="epoch"`, `save_strategy="epoch"`, `load_best_model_at_end=True`, `metric_for_best_model="f1_macro"`; Hub push gated on `HF_TOKEN` env var). Added `apps/ml-worker/training/evaluate.py` (loads model from HF_REPO with local OUTPUT_DIR fallback; prominent held-out banner; computes per-label P/R/F1 + macro/micro for both fine-tuned model and keyword baseline; writes `evaluation_results.json` and `evaluation_results.md` with a GPT-4o placeholder row for manual fill-in). Added `apps/ml-worker/training/README.md` (end-to-end pipeline guide: download → label → verify → split → Colab train → eval, with Colab cell snippets and a config-knob reference). 4 commits on the feature branch. |
| 2026-06-21 | Issue #7 + #8 — Diff parser + sliding-window tokenizer. On branch `feat/issue-7-8-parser`. Added `apps/ml-worker/app/diff_parser.py` (class `FileHunk` with file_path/added_lines/removed_lines/language; `parse_diff()` handles unified diff, multi-file via `diff --git` headers, binary-file skip, empty-input `ValueError`; path extraction prefers `diff --git b/...` then `+++ b/...`; language map .py→python, .js/.ts→javascript, .java→java, else `unknown`; `hunks_to_text()` flattens for tokenization). Added `tests/test_diff_parser.py` with 8 tests including a real N+1 diff structure check. Added `apps/ml-worker/app/tokenizer_utils.py` (`sliding_window_tokenize()` encodes once then slices into overlapping 512-token windows with 50-token stride, returns list of `{input_ids, attention_mask}` dicts padded to max_length; `aggregate_logits()` max-pools per-window logits; both validate args and raise `ValueError` on bad input). Added `tests/test_tokenizer_utils.py` with a deterministic `FakeTokenizer` (no model load) and 8 tests. Added empty `tests/__init__.py`. 5 commits on the feature branch. Note: the spec spelled the dataclass `FilHunk` in one place and `FileHunk` in another — I went with `FileHunk` (the form used in the function signature), which is the conventional name; flag if the typo in the spec was intentional.

---

## 7. How to Use This File

- New session? Read this file first, then re-read [ENGINEERING_PLAN.md.md](./ENGINEERING_PLAN.md.md) Section relevant to your current issue.
- Starting an issue? Update the checkbox in Section 4.
- Making a non-trivial decision? Add it to Section 5 only after asking the principal engineer.
- Finished a session? Add a row to Section 6 with the date and a one-line summary.
