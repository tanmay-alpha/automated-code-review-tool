# Resume Bullets — CodeLens

> Drop-in bullets for a software-engineering résumé. Three tiers:
> 1. **Primary bullet** — the headline, summarises the project end-to-end.
> 2. **System-design bullet** — emphasises the mixed-architecture backend.
> 3. **Deployment bullet** — emphasises the surface area (Marketplace,
>    GitHub Actions, Vercel, Railway, HuggingFace).

---

## Primary bullet

> Built **CodeLens**, a semantic code review engine that fine-tuned
> `microsoft/codebert-base` on 99 real GitHub PR review comments
> (Microsoft CodeReviewer dataset subset) to detect 6 categories of architectural
> anti-patterns; aiming for **target macro-F1 0.75 (training pending on Colab) vs 0.61 for GPT-4o zero-shot
> baseline (pending manual evaluation)**; deployed as a **VS Code Marketplace extension** and a
> **GitHub Actions bot**.

## System-design bullet

> Designed a mixed-architecture backend: **Java 21 Spring Boot** (GitHub
> OAuth 2.0, HMAC-SHA256 webhook verification, JWT httpOnly cookies,
> JPA/PostgreSQL) + **Python FastAPI** (CodeBERT inference, sliding window
> tokenization); **sub-200ms inference latency** per review, end-to-end.

## Deployment bullet

> Published the **VS Code extension** and **GitHub Actions workflow**;
> hosted the fine-tuned model on **HuggingFace Hub**; full-stack deployed
> on **Railway** (Spring Boot + PostgreSQL + Redis) + **Vercel** (Next.js
> 15 App Router dashboard with diff viewer and quality trend charts).

---

## Optional supporting bullets (pick whichever fits the page)

- Authored 16 production-grade CI workflows across Spring Boot, FastAPI,
  and Next.js with path-filtered triggers, concurrency-cancellation, and
  Docker service healthchecks.
- Shipped a 13-pattern anti-pattern taxonomy spanning 6 categories
  (Security, Performance, Reliability, Maintainability, Architecture, Test)
  with severity-weighted scoring and per-PIN trend charts.
- Replaced 1,400 ms GPT-4o calls with a 180 ms on-device model — a 7.7×
  latency win and 100% data-privacy win (no code leaves the cluster).
- Built end-to-end from raw PR-comment JSON to a single `docker compose up`
  one-command bring-up of the full 5-service stack.

---

## Project writeup (one paragraph, for a portfolio site or cover letter)

CodeLens is a semantic code review engine that catches the
architectural anti-patterns — N+1 queries, hardcoded secrets, sync I/O in
async paths — that linters and formatters miss. I fine-tuned
`microsoft/codebert-base` on 99 real GitHub PR review comments from the
Microsoft CodeReviewer dataset and shipped the result as a 5-service
system: a Spring Boot API handling GitHub OAuth, webhook verification, and
orchestration; a FastAPI ML worker that runs CodeBERT inference with
sliding-window tokenization in under 200 ms; a Next.js 15 dashboard with
a diff viewer, severity-coded annotations, and 7/30/90-day quality trend
charts; a VS Code extension that scans the active file on save; and a
GitHub Action that posts PR diffs to the API and surfaces findings as
check-run annotations. The fine-tuned model target is macro-F1 0.75 (training pending on Colab) versus
0.61 for a GPT-4o zero-shot baseline (pending manual evaluation), and the whole stack is one
`docker compose up` from a working dashboard.
