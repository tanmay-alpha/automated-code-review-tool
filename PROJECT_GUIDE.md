# 🎓 CodeLens — Explained Like You're 5 (But Interview-Ready!)

> A **fun, colorful, zero-jargon** guide to your project. If someone asks
> "Explain CodeLens" in an interview, **read this file out loud** and you'll sound
> like the person who built every line.

---

<div align="center">

## 🌈 The 30-Second Elevator Pitch

```
╔══════════════════════════════════════════════════════════════╗
║                                                              ║
║   "CodeLens is an AI that reads your code the way a          ║
║    senior engineer does — it catches dumb mistakes that      ║
║    linters can't see. It runs inside VS Code, in GitHub       ║
║    PRs, and on a web dashboard. I built it end-to-end."      ║
║                                                              ║
╚══════════════════════════════════════════════════════════════╝
```

</div>

---

## 🧸 Real-Life Analogy First (the "kitchen" story)

Imagine you invite a friend to cook pasta 🍝. They walk into your kitchen and do this:

| ❌ What a *normal* friend does | ✅ What a *senior chef* friend does |
|---|---|
| Notices your pasta is **overcooked** (syntax error) | Notices you're **boiling pasta in cold water** (logic error) |
| Warns you with `eslint` / `pylint` | Warns you: *"bro, that's not how risotto works"* |
| Catches typos | Catches **architectural disasters** |

**CodeLens = the senior chef friend.** It reads the *meaning* of your code, not just the spelling.

A concrete example from your project:

```python
# 🐛 Linters (ESLint, pylint) say: "Looks fine!"
for u in users:
    posts = Post.where(user=u)   # one query per user — slow!
```

```python
# ✅ CodeLens says: "PERFORMANCE_N_PLUS_1 — major bug, fix it!"
posts = Post.where(user_id__in=[u.id for u in users])
```

That's the **whole point** of CodeLens. Now let's see how it actually does this.

---

## 🗺️ The Big Picture (3D Map of the System)

```
                        👤 YOU (the developer)
                              │
                ┌─────────────┼─────────────┐
                ▼             ▼             ▼
          ┌─────────┐   ┌──────────┐   ┌──────────┐
          │  💻     │   │  🌐      │   │  🤖      │
          │ VS Code │   │ Next.js  │   │ GitHub   │
          │  Ext    │   │Dashboard │   │ Action   │
          └────┬────┘   └─────┬────┘   └─────┬────┘
               │              │              │
               │  "Hey, review this code for me!"  │
               └──────────────┼──────────────┘
                              ▼
                  ┌───────────────────────┐
                  │   ☕ Spring Boot API   │   ← Java 21
                  │  (the bouncer/manager)│
                  │  • GitHub login       │
                  │  • Webhook receiver   │
                  │  • API key checker    │
                  └──────┬─────────┬──────┘
                         │         │
                💾 saves  │         │  🧠 asks the AI brain
                         ▼         ▼
                  ┌──────────┐  ┌──────────────┐
                  │ 🐘       │  │ 🐍 FastAPI    │   ← Python
                  │PostgreSQL│  │ ML Worker     │
                  │ + Redis  │  │ (the AI brain)│
                  └──────────┘  └──────┬───────┘
                                       │
                                       ▼
                              ┌─────────────────┐
                              │ 🧬 CodeBERT     │   ← HuggingFace
                              │ (the AI model)  │
                              └─────────────────┘
```

### What each box actually does (in plain English)

| Box | Role | Real-life analogy |
|---|---|---|
| **VS Code Extension** | Asks CodeLens to review the file you're editing | Your friend peeking at your screen |
| **Next.js Dashboard** | Web page that shows charts of code quality over time | Your phone showing health stats |
| **GitHub Action** | Auto-comments on every PR with bug warnings | A robot that patrols your front door |
| **Spring Boot API (Java)** | The boss — handles logins, security, database | The restaurant manager |
| **PostgreSQL** | Remembers users, repos, past PRs | The filing cabinet |
| **Redis** | Tiny speed-memory for "did this user spam us?" | A notepad on the manager's desk |
| **FastAPI ML Worker (Python)** | Loads the AI brain and runs it | The lab where the brain lives |
| **CodeBERT (HuggingFace)** | The AI model that actually finds the bugs | The brain itself |

---

## 🎨 The Tech Stack — *Why each piece exists* (in plain English)

> **You said you vibe-coded this and don't know the tech stack. Here's the cheat sheet.**

### 🟧 Java 21 + Spring Boot 3.3 — *The Manager*

**What it is:** A programming language + a big toolbox for building web APIs.

**Why we picked it:**
- ☕ **Battle-tested** — banks, Netflix, Amazon use it
- 🔒 **Security built-in** — logins, passwords, encryption come free
- 📊 **Talks to databases easily** — one annotation and you're done
- 🚀 **Industry standard** — every interviewer has seen it

**The vibe:** Imagine hiring a manager for your restaurant. Spring Boot is a manager who comes with a checklist, a clipboard, and knows every health-code law.

### 🐍 Python 3.11 + FastAPI — *The Lab Assistant*

**What it is:** Python is the language AI people love. FastAPI is a tiny toolbox that turns Python into a fast web server.

**Why we picked it:**
- 🧠 **AI libraries** — `transformers`, `torch`, `huggingface` — all Python first
- ⚡ **FastAPI is genuinely fast** — as quick as Node.js
- 📝 **Auto-generated docs** — the API page writes itself

**The vibe:** The lab assistant who actually knows how to run experiments. Java would be overkill for the brain work.

### 🧬 microsoft/codebert-base — *The Brain*

**What it is:** A pre-trained AI that already understands code (it read millions of GitHub repos during training). We **fine-tuned** it on 50,000 real PR review comments.

**Why we picked it:**
- 🎯 **Already knows code** — we don't train from scratch
- 📦 **Small enough to run on a laptop** — 125 million parameters
- 🏆 **Beats GPT-4o on our task** — see the eval table below

**The vibe:** A junior engineer who's read every programming book. We gave them 50,000 examples of "good code review comments" and now they write like a senior.

### ⚛️ Next.js 15 + TypeScript — *The Shop Window*

**What it is:** A modern way to build web pages. Next.js handles both the server and the browser.

**Why we picked it:**
- 🎨 **Looks pretty out of the box**
- 🔄 **Re-renders only what changed** — fast
- 📱 **Mobile-friendly for free**

**The vibe:** The shop window. People land on it, see how cool the product is, and click "login with GitHub".

### 🐘 PostgreSQL + Redis — *The Filing Cabinet + Notepad*

**What it is:** Two databases. PostgreSQL = main storage (users, repos, history). Redis = tiny fast memory (rate limits, webhooks dedup).

**Why we picked them:**
- 🐘 **Postgres** = the *only* free database you should ever use for serious work
- 🚀 **Redis** = so fast it answers in microseconds; perfect for "did this webhook arrive before?"

**The vibe:** PostgreSQL is the company's filing room. Redis is the post-it note on the front desk.

### 🔐 JWT + httpOnly cookies + GitHub OAuth — *The VIP Wristband*

**What it is:** When you log in with GitHub, the server gives your browser a tamper-proof token (JWT). Every future request shows this token.

**Why this combo:**
- 🍪 **httpOnly cookie** = JavaScript can't steal it (XSS-safe)
- 🎫 **JWT** = the server doesn't need to remember you (stateless)

**The vibe:** A nightclub wristband. Once you're inside, you flash it at every door.

### 🐳 Docker + docker-compose — *The Shipping Container*

**What it is:** A way to package the whole app so it runs the same on your laptop, your friend's laptop, and a server in the cloud.

**Why we picked it:**
- 🚢 **One command runs everything** — `docker compose up`
- 🌍 **Same behavior everywhere** — "works on my machine" → dead

**The vibe:** Instead of sending IKEA furniture with 200 loose screws, you send it assembled in a box.

### 🚂 Railway + Vercel + HuggingFace — *The Three Neighborhoods*

Where each piece lives in production:

| Service | Hosted on | Why |
|---|---|---|
| Spring Boot API + Postgres + Redis | **Railway** | One click, they handle the database too |
| Next.js Dashboard | **Vercel** | Built by the Next.js team — perfect fit |
| CodeBERT model | **HuggingFace Hub** | Free hosting for AI models, version-controlled |
| VS Code Extension | **VS Code Marketplace** | Where every dev finds extensions |

---

## 📊 The Numbers (the part that impresses interviewers)

We tested CodeLens on 1,000 PR comments it had **never seen before**:

| Model | Macro-F1 | Precision | Recall | Speed |
|---|---|---|---|---|
| **🥇 CodeLens (CodeBERT)** | **0.75** | 0.78 | 0.73 | 180 ms |
| GPT-4o (zero-shot) | 0.61 | 0.66 | 0.57 | 1,400 ms |
| Keyword regex | 0.44 | 0.92 | 0.21 | 5 ms |

**Story for the interview:**
> *"I fine-tuned a 125M-parameter CodeBERT on 50K real PR comments and beat GPT-4o by 14 F1 points while running **7× faster**. The keyword baseline got 0.92 precision but only 0.21 recall — it can't catch anything it wasn't hand-coded to match. That's why ML > regex for semantic tasks."*

---

## 🗂️ The Folder Map — *"Which file does what?"*

> **This is the interview cheat sheet.** Memorize this and you can answer
> *"walk me through the codebase"* with confidence.

### Top-level layout

```
codelens/
├── apps/                  ← everything that runs
│   ├── api/               ← Java backend
│   ├── ml-worker/         ← Python AI brain
│   ├── web/               ← Next.js dashboard
│   └── vscode-ext/        ← VS Code plugin
├── github-action/         ← GitHub Actions bot
├── infra/                 ← Docker Compose + env templates
├── docs/                  ← architecture, eval, API reference
├── .github/workflows/     ← CI/CD (3 test workflows + deploy)
├── CONTEXT.md             ← project memory (the brain of this repo)
├── ENGINEERING_PLAN.md    ← the original spec (1,400 lines)
├── README.md              ← landing page for GitHub
├── LINKEDIN_POST.md       ← copy-paste LinkedIn announcement
└── RESUME.md              ← resume bullets for your CV
```

### 🟧 `apps/api/` — The Java Backend

> *"This is the brain's bodyguard. It decides who's allowed to ask questions,
> stores the answers, and routes requests to the AI brain."*

```
apps/api/src/main/java/com/codelens/
├── CodeLensApplication.java       ← Spring Boot entry point (run this)
├── config/                        ← settings (security, async, etc.)
│   ├── SecurityConfig.java        ← who can call what
│   ├── JwtConfig.java             ← JWT secret + expiry times
│   ├── AsyncConfig.java           ← background-thread pool for webhooks
│   └── AppConfig.java, GitHubConfig.java
│
├── controller/                    ← HTTP endpoints (URLs the world calls)
│   ├── AuthController.java        ← /api/auth/github, /callback, /refresh
│   ├── WebhookController.java     ← /api/webhook/github (GitHub calls us)
│   ├── RepoController.java        ← /api/repos/connect, /list
│   ├── ScanController.java        ← /api/scan/file (VS Code endpoint)
│   ├── ReviewController.java      ← /api/review (dashboard endpoint)
│   ├── MetricsController.java     ← /api/metrics/quality-trend
│   └── ApiKeyController.java      ← /api/auth/api-keys
│
├── service/                       ← business logic (does the actual work)
│   ├── UserService.java           ← find-or-create users from GitHub login
│   ├── GitHubService.java         ← talks to github.com (OAuth, webhooks)
│   ├── WebhookService.java        ← async PR processing
│   ├── RepoService.java           ← connect/disconnect repos
│   ├── ReviewService.java         ← ties findings to PRs
│   ├── MlWorkerService.java       ← calls the Python brain
│   └── ApiKeyService.java         ← mints VS Code API keys
│
├── security/                      ← locks and keys
│   ├── JwtService.java            ← generates + validates JWTs
│   ├── JwtAuthFilter.java         ← reads cookie, sets principal
│   ├── ApiKeyAuthFilter.java      ← reads "Bearer cl_live_..."
│   ├── AuthRateLimitFilter.java   ← 10 req/min per IP on /api/auth/*
│   └── EncryptionService.java     ← AES-256-GCM for secrets
│
├── entity/                        ← database tables (JPA)
│   ├── User.java
│   ├── Repository.java
│   ├── PullRequestEntity.java
│   ├── Finding.java
│   ├── QualityMetric.java
│   ├── ApiKey.java
│   └── ProcessedWebhook.java
│
├── repository/                    ← database queries (Spring Data)
│   ├── UserRepository.java
│   ├── RepositoryRepository.java
│   ├── PullRequestRepository.java
│   ├── FindingRepository.java
│   ├── QualityMetricRepository.java
│   ├── ApiKeyRepository.java
│   └── ProcessedWebhookRepository.java
│
├── webhook/                       ← incoming GitHub events
│   ├── HmacVerifier.java          ← checks signatures (timing-safe)
│   └── GitHubWebhookEvent.java    ← JSON shape
│
├── dto/                           ← request/response shapes
│   ├── request/                   ← what clients send
│   └── response/                  ← what we send back
│
├── exception/                     ← custom error types
│   ├── GlobalExceptionHandler.java ← turns exceptions into JSON errors
│   └── ConnectRepoException.java
│
└── resources/
    ├── application.yml            ← main config
    ├── application-dev.yml
    └── db/migration/              ← Flyway SQL (V1, V2, V3, ...)
        ├── V1__initial_schema.sql
        ├── V2__quality_metrics.sql
        └── V3__api_keys.sql
```

### 🐍 `apps/ml-worker/` — The Python AI Brain

> *"This is the lab where the brain actually runs. Spring Boot calls it
> over HTTP, it loads CodeBERT, and returns the bugs it found."*

```
apps/ml-worker/
├── app/                           ← the running brain
│   ├── main.py                    ← FastAPI server (the entry point)
│   ├── model.py                   ← CodeLensModel class — loads CodeBERT
│   ├── diff_parser.py             ← parses "before/after" code diffs
│   ├── tokenizer_utils.py         ← splits long code into 512-token chunks
│   ├── schemas.py                 ← Pydantic models (request/response shapes)
│   └── config.py                  ← env vars (HF token, model name, etc.)
│
├── training/                      ← where the brain was *taught*
│   ├── train.py                   ← fine-tunes CodeBERT on Google Colab
│   ├── evaluate.py                ← measures accuracy on the test set
│   ├── dataset.py                 ← loads the CodeReviewer dataset
│   ├── label_mapper.py            ← turns comments into 6 categories
│   ├── split.py                   ← 80/10/10 train/val/test by PR ID
│   ├── verify_sample.py           ← human-in-the-loop label check
│   ├── README.md                  ← step-by-step training guide
│   └── data/                      ← train.json / val.json / test.json
│
├── tests/                         ← pytest suite
│   ├── test_api.py                ← HTTP endpoint tests
│   ├── test_diff_parser.py
│   ├── test_tokenizer_utils.py
│   ├── test_label_mapper.py
│   └── conftest.py                ← shared test fixtures
│
├── Dockerfile                     ← packages the brain for Docker
└── requirements.txt               ← Python packages it needs
```

### ⚛️ `apps/web/` — The Next.js Dashboard

> *"This is what users actually see when they go to codelens.dev. Pretty
> charts, log in with GitHub, view their PR reviews."*

```
apps/web/src/
├── app/                           ← Next.js 15 App Router (pages)
│   ├── layout.tsx                 ← the wrapper every page uses
│   ├── page.tsx                   ← landing page (/)
│   ├── dashboard/                 ← /dashboard — quality charts
│   ├── pr/                        ← /pr/[id] — diff viewer
│   ├── repo/                      ← /repo/[id] — repo detail
│   ├── settings/                  ← /settings — user settings + API keys
│   ├── taxonomy/                  ← /taxonomy — what patterns we catch
│   └── api/                       ← /api/* — server-side routes
│
├── components/                    ← reusable UI bits
│   ├── ui/                        ← shadcn/ui (buttons, cards, dialogs)
│   ├── diff-viewer.tsx            ← side-by-side code diff
│   ├── finding-annotation.tsx     ← red/yellow squiggle on bad code
│   └── quality-chart.tsx          ← recharts trend chart
│
├── lib/                           ← shared TypeScript helpers
│   ├── api-client.ts              ← talks to Spring Boot
│   ├── auth.ts                    ← JWT cookie helpers
│   └── utils.ts                   ← classNames, date formatting
│
└── globals.css                    ← Tailwind base styles
```

### 💻 `apps/vscode-ext/` — The VS Code Plugin

> *"This is what runs inside VS Code. When you save a file, it sends the code
> to the API and shows red squiggly underlines where there are bugs."*

```
apps/vscode-ext/src/
├── extension.ts                   ← VS Code entry point (activates on save)
├── reviewer.ts                    ← calls /api/scan/file, gets findings
└── config.ts                      ← reads apiUrl + apiKey from settings
```

### 🤖 `github-action/` — The GitHub Bot

> *"Drop this YAML into any repo and CodeLens auto-comments on every PR."*

```
github-action/
├── action.yml                     ← GitHub Action metadata (inputs, branding)
├── index.js                       ← entry point — calls the API
├── package.json
└── test/                          ← Jest tests
```

### 🚂 `infra/` — The Docker Compose

> *"One file that runs postgres + redis + ml-worker + api + web on your laptop."*

```
infra/
├── docker-compose.yml             ← the whole stack, one command
├── .env.example                   ← every env var documented
└── README.md
```

### ⚙️ `.github/workflows/` — The CI/CD

> *"Every push to main runs these automatically."*

```
.github/workflows/
├── ci-api.yml                     ← tests Java API (mvn test)
├── ci-ml-worker.yml               ← tests Python brain (pytest)
├── ci-web.yml                     ← typechecks Next.js (tsc --noEmit)
└── deploy-prod.yml                ← on main → fires Railway + Vercel webhooks
```

---

## 🔄 How a Request Actually Flows (Real-Life Walkthrough)

> Let's trace what happens **second by second** when someone opens a PR on GitHub.

### Scenario: You push a PR to your repo.

```
Step 1  ⏱️  T+0ms
   GitHub sees the new PR.
   GitHub sends a POST to YOUR server:
   POST https://api.codelens.dev/api/webhook/github
   Headers: X-Hub-Signature-256: sha256=abc...
   Body: {"action": "opened", "pull_request": {...}}
        │
        ▼
Step 2  ⏱️  T+5ms    🟧 WebhookController.java
   Receives the POST.
   Reads the signature header.
   Calls HmacVerifier to check the signature.
        │
        ▼
Step 3  ⏱️  T+15ms   🟧 HmacVerifier.java
   Looks up the repo's secret in PostgreSQL.
   Decrypts it with EncryptionService.
   Recomputes HMAC-SHA256(payload, secret).
   Compares with MessageDigest.isEqual (timing-safe).
   ✅ Match → continue.  ❌ No match → 401, stop.
        │
        ▼
Step 4  ⏱️  T+25ms   🟧 WebhookService.java (async — on a background thread)
   Saves the delivery ID to "processed_webhooks" (dedup).
   Pulls the PR diff from GitHub via GitHubService.getFileDiff().
        │
        ▼
Step 5  ⏱️  T+200ms  🟧→🐍 MlWorkerService.java → POST /ml/review
   Forwards the diff to the Python brain.
        │
        ▼
Step 6  ⏱️  T+250ms  🐍 FastAPI main.py
   Checks the X-ML-Worker-Secret header (hmac.compare_digest).
   Loads the diff into CodeLensModel.predict().
        │
        ▼
Step 7  ⏱️  T+280ms  🐍 model.py
   Splits the diff into 512-token windows (tokenizer_utils.py).
   Runs each window through microsoft/codebert-base.
   Sigmoid → threshold 0.5 → list of findings.
   Returns: [{category: "PERFORMANCE_N_PLUS_1", ...}, ...]
        │
        ▼
Step 8  ⏱️  T+450ms  🟧 WebhookService.java
   Saves the findings to PostgreSQL (Finding table).
   Computes a quality score (100 - 20*critical - 10*major - 3*minor).
   Posts the findings as a comment on the PR via GitHubService.
        │
        ▼
Step 9  ⏱️  T+700ms  ✅ User sees:
   🤖 CodeLens commented on your PR:
   ⚠️  PERFORMANCE_N_PLUS_1 — major
       for u in users: posts = Post.where(user=u)
       Fix: posts = Post.where(user_id__in=[u.id for u in users])
```

**Total time: under a second.** 🎉

---

## 🛡️ Security Features (great interview topic)

| What | Where | Why it matters |
|---|---|---|
| **HMAC-SHA256 webhook verification** | `webhook/HmacVerifier.java` | Only GitHub can call our webhook |
| **AES-256-GCM encryption** | `security/EncryptionService.java` | GitHub tokens + webhook secrets encrypted at rest |
| **BCrypt for API keys** | `service/ApiKeyService.java` | Even if DB leaks, keys aren't reversible |
| **JWT in httpOnly cookies** | `security/JwtService.java` | JavaScript can't steal the session |
| **Rate limiting (auth: 10/min, API key: 60/min)** | `security/*RateLimitFilter.java` | Stops brute-force attacks |
| **Timing-safe comparisons** | `hmac.compare_digest` and `MessageDigest.isEqual` | Stops timing-attack leaks |
| **Pinned dependency versions** | `pom.xml`, `requirements.txt` | Reproducible builds |

---

## 🧪 Tests — *"How do I know it works?"*

| Component | Test runner | Number of tests | Coverage focus |
|---|---|---|---|
| Java API | `mvn test` | 56+ | Security filters, controllers, services |
| Python brain | `pytest -m "not slow"` | 30+ | Diff parser, tokenizer, label mapper, API |
| Next.js | `tsc --noEmit` | typecheck | No runtime tests yet (TODO) |

**Run them yourself:**
```bash
cd apps/api          && mvn test -q
cd apps/ml-worker    && python -m pytest tests/ -m "not slow" -q
cd apps/web          && npx tsc --noEmit
```

---

## 🎤 The Interview Script (memorize this)

> *"Tell me about a project you're proud of."*

**The 60-second answer:**

> *"I built **CodeLens**, an AI code review engine that catches architectural
> anti-patterns that linters miss — things like N+1 database queries, hardcoded
> secrets, and sync I/O inside async functions.*
>
> *It's a five-service system:*
> - *a **Java 21 + Spring Boot API** that handles GitHub OAuth, webhook verification, and stores everything in PostgreSQL,*
> - *a **Python + FastAPI ML worker** that runs a fine-tuned CodeBERT model with sliding-window tokenization,*
> - *a **Next.js dashboard** that shows quality trends over time,*
> - *a **VS Code extension** that scans files on save, and*
> - *a **GitHub Actions bot** that auto-comments on every PR.*
>
> *I fine-tuned `microsoft/codebert-base` on 50,000 real GitHub PR review comments
> from the Microsoft CodeReviewer dataset. On a held-out 1,000-comment test set,
> it hits **macro-F1 0.75** — that's 14 points better than GPT-4o zero-shot, and
> it runs in 180ms instead of 1,400ms.*
>
> *The trickiest parts were security: I had to implement timing-safe HMAC
> verification for GitHub webhooks, AES-256-GCM encryption for OAuth tokens at
> rest, and rate limiting to prevent brute-force on the auth endpoints. The
> whole stack runs with one `docker compose up` command."*

**Then the interviewer will ask one of these. Be ready:**

<details>
<summary><b>"Why CodeBERT instead of a bigger LLM?"</b></summary>

> *Latency and cost. GPT-4o is 8× slower (1.4s vs 180ms) and costs money per
> call. A fine-tuned small model runs on CPU, never sends code to a third party
> (privacy win for enterprise), and beats the big model on this specific task
> because it's seen 50K domain-specific examples.*

</details>

<details>
<summary><b>"How does the sliding window work?"</b></summary>

> *CodeBERT can only read 512 tokens at a time, but a real PR diff can be
> thousands of lines. So we tokenize the whole diff once, then slice it into
> overlapping 512-token windows with a 50-token stride. We run each window
> through the model independently, then max-pool the logits across windows —
> so any bug mentioned anywhere in the diff gets caught.*

</details>

<details>
<summary><b>"Why split by PR ID instead of randomly?"</b></summary>

> *Data leakage prevention. If we split rows randomly, the same PR's "before"
> and "after" comments can land in different splits — the model has seen the
> answer at training time and the test score is fake. Splitting by PR ID
> guarantees the model has never seen that PR's repo before.*

</details>

<details>
<summary><b>"How do webhooks stay secure?"</b></summary>

> *GitHub signs every webhook payload with HMAC-SHA256 using a per-repo secret
> we generate when the user installs the webhook. When a webhook arrives, we
> recompute the signature with the stored secret and compare using
> `MessageDigest.isEqual` — that's timing-safe so an attacker can't probe our
> secret byte-by-byte by measuring response times.*

</details>

<details>
<summary><b>"What would you improve next?"</b></summary>

> *Three things on the roadmap: incremental review caching (skip files that
> didn't change — ~70% speedup on big PRs), a single multilingual checkpoint
> (currently one model per language), and auto-fix suggestions — generate a
> one-line code change per finding so reviewers can accept and collapse.*

</details>

---

## 🧠 Quick-Reference Glossary

> Plain English for any jargon an interviewer might throw at you.

| Term | Meaning in one sentence |
|---|---|
| **JWT** | A signed token the server gives you after login; you flash it on every request. |
| **HMAC** | A way to prove a message wasn't tampered with — like a wax seal on a letter. |
| **OAuth** | "Login with Google/GitHub" — we never see your password. |
| **Webhook** | One server calls another when something happens (GitHub → us on every PR). |
| **Rate limit** | "Only 10 requests per minute" — stops bad guys from spamming us. |
| **Fine-tuning** | Taking a smart AI and teaching it to be smart at *your* specific task. |
| **Sliding window** | Reading a long document by reading 512 words at a time with overlap. |
| **Macro-F1** | A score from 0 to 1: how good is the AI at catching every category? Higher = better. |
| **Monorepo** | One Git repo that holds many apps. Like a fridge with all your food together. |
| **CI/CD** | Automatically testing and shipping code every time you push. |

---

<div align="center">

## ✅ TL;DR — The One-Sentence Summary

```
╔══════════════════════════════════════════════════════════════════╗
║  CodeLens is a 5-service system that uses a fine-tuned          ║
║  CodeBERT model to find code-review bugs faster and more         ║
║  accurately than GPT-4o, with a Spring Boot backend,             ║
║  Python ML worker, Next.js dashboard, VS Code plugin, and        ║
║  GitHub Actions bot — all runnable with `docker compose up`.      ║
╚══════════════════════════════════════════════════════════════════╝
```

**You've got this.** 🎯

</div>