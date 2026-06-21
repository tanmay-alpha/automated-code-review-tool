# CodeLens

> **An AI bot that reviews your GitHub Pull Requests and points out common code problems before you merge.**
>
> Deep technical spec: [ENGINEERING_PLAN.md.md](./ENGINEERING_PLAN.md.md) · Project memory: [CONTEXT.md](./CONTEXT.md)

---

## What it does

You open a PR → within seconds, CodeLens reads your code changes and comments back with specific problems it found: *"this is a database query inside a loop"*, *"this password is hardcoded"*, *"this function does too many things"*.

The same AI also works as a **VS Code extension** (red squiggles while you type) and a **GitHub Action** (drop it into any repo's CI).

---

## How it works

```
   You open PR #42
        │
        ▼
   GitHub ──webhook──▶  Spring Boot API  ──HTTP──▶  FastAPI ML Worker
   (HMAC verified)        (Java, port 8080)         (Python, port 8000)
                                │                          │
                                │   ◀───findings JSON─────┘
                                ▼
                          Posts comment on PR
                          Saves to PostgreSQL
                          Updates quality score
```

End-to-end latency: **~5 seconds** from PR open to first CodeLens comment.

---

## The 6 things it catches

| # | Category | What it means | Tiny example |
|---|---|---|---|
| 1 | **SECURITY** | Secrets, injection risks | `password = "abc123"` written in code |
| 2 | **PERFORMANCE** | Slow patterns | SQL query inside a `for` loop |
| 3 | **ARCHITECTURE** | One class/function doing too much | Class with 30 methods + 2000 lines |
| 4 | **RELIABILITY** | Bugs that crash at runtime | `except:` that swallows every error |
| 5 | **READABILITY** | Confusing code | `x = 86400` with no comment |
| 6 | **MAINTAINABILITY** | Hard to change later | Same code copy-pasted in 5 places |

Each finding has a **severity** (critical / major / minor) and a **confidence score** (0–100%).

---

## Worked examples — bad code → what CodeLens says

### Example 1: PERFORMANCE (the classic N+1 query)

```python
# ❌ BAD — runs 1 query per user (slow at scale)
def get_orders(user_ids):
    orders = []
    for uid in user_ids:
        result = db.execute(f"SELECT * FROM orders WHERE user_id = {uid}")
        orders.extend(result.fetchall())
    return orders
```

**CodeLens comments on the PR:**

> ⚠️ **Performance — N+1 Query** (line 3, confidence 91%)
> Database query inside a loop. Move the query outside the loop or use a bulk fetch.
> Quality score: **68/100**

### Example 2: SECURITY (hardcoded password)

```python
# ❌ BAD
def connect_to_db():
    return psycopg2.connect(
        host="prod-db",
        password="SuperSecret123",   # ← right there in the code
    )
```

**CodeLens:**

> 🔴 **Security — Hardcoded Secret** (line 4, confidence 97%)
> Password stored in source code. Move to environment variables or a secrets manager.

### Example 3: RELIABILITY (bare except)

```python
# ❌ BAD — hides every error
try:
    process_payment(order)
except:
    pass
```

**CodeLens:**

> 🟠 **Reliability — Bare Except** (line 3, confidence 88%)
> Bare `except:` swallows all errors including KeyboardInterrupt. Catch a specific exception type and log it.

---

## The 5 services

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│   apps/api            Spring Boot (Java 21)        :8080    │
│                       brain: login, webhooks, DB, GitHub   │
│                                                             │
│   apps/ml-worker      FastAPI (Python 3.11)       :8000    │
│                       AI: loads CodeBERT, runs inference   │
│                                                             │
│   apps/web            Next.js 15 (TypeScript)     :3000    │
│                       dashboard: repos, PRs, charts        │
│                                                             │
│   apps/vscode-ext     VS Code plugin (TypeScript) —        │
│                       squiggles + hover while you code     │
│                                                             │
│   github-action/      Reusable GitHub Action      —        │
│                       drop into any repo's CI              │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## Tech stack

| Layer | Choice |
|---|---|
| API | Java 21 + Spring Boot 3.3 |
| ML worker | Python 3.11 + FastAPI |
| AI model | CodeBERT (Microsoft, 125M params, fine-tuned on real code reviews) |
| Frontend | Next.js 15 + TypeScript + Tailwind |
| Database | PostgreSQL 16 |
| Cache | Redis 7 (webhook dedup only) |
| Auth | GitHub OAuth 2.0 + JWT (stored in httpOnly cookies) |
| Hosting | Docker + Railway + Vercel |

**Total hosting cost: ~$10/month.**

---

## How the AI gets trained

1. Download **150,000 real code review comments** from Microsoft's open-source CodeReviewer dataset.
2. Filter out noise ("nit", "typo") → ~55,000 useful samples left.
3. Tag each comment with one or more of our 6 categories using keyword matching.
4. A human spot-checks 500 samples to confirm the tags.
5. Fine-tune CodeBERT on the tagged data (a few hours on a free GPU).
6. Test against two baselines — pure keyword matching and GPT-4o zero-shot — ship only if it beats both.

---

## The 30-day plan

```
Days 1-7   │  Train the AI model on real code review data
Day 9      │  🎯 Demo 1:  curl returns real AI findings
Days 10-15 │  Build API + GitHub login + webhooks
Day 15     │  🎯 Demo 2:  open a real PR → CodeLens comments
Days 16-23 │  Build the dashboard website
Days 24-27 │  Build the VS Code extension
Days 28-30 │  Build the GitHub Action + deploy + demo video
```

**Two demos are mandatory checkpoints.** After Day 15 the hard problems are solved.

---

## Status 

- ✅ Repo scaffolded — folder structure, env, gitignore
- ✅ Issue #1 in progress — dataset download + inspection script
- ⏳ 19 issues remaining across 4 milestones

Full task list: see the issue tracker in [CONTEXT.md](./CONTEXT.md).
