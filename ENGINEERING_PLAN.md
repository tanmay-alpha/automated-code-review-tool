# CodeLens — Principal Engineer Pre-Implementation Plan
> Written before a single line of code is committed.
> Every decision here is final unless a blocker forces a revision.

---

## 1. MONOREPO FOLDER STRUCTURE

```
codelens/
│
├── apps/
│   ├── api/                          # Spring Boot — Java 21
│   │   ├── src/
│   │   │   └── main/
│   │   │       ├── java/com/codelens/
│   │   │       │   ├── CodeLensApplication.java
│   │   │       │   ├── config/
│   │   │       │   │   ├── SecurityConfig.java
│   │   │       │   │   ├── WebClientConfig.java       # For calling FastAPI
│   │   │       │   │   └── AsyncConfig.java
│   │   │       │   ├── controller/
│   │   │       │   │   ├── AuthController.java
│   │   │       │   │   ├── WebhookController.java
│   │   │       │   │   ├── RepoController.java
│   │   │       │   │   ├── ReviewController.java
│   │   │       │   │   └── MetricsController.java
│   │   │       │   ├── service/
│   │   │       │   │   ├── GitHubService.java         # All GitHub API calls
│   │   │       │   │   ├── ReviewService.java         # Orchestrates review flow
│   │   │       │   │   ├── MlWorkerService.java       # Calls FastAPI
│   │   │       │   │   ├── WebhookService.java        # Async webhook processing
│   │   │       │   │   └── MetricsService.java
│   │   │       │   ├── repository/
│   │   │       │   │   ├── UserRepository.java
│   │   │       │   │   ├── RepositoryRepo.java
│   │   │       │   │   ├── PullRequestRepository.java
│   │   │       │   │   ├── FindingRepository.java
│   │   │       │   │   └── QualityMetricRepository.java
│   │   │       │   ├── entity/
│   │   │       │   │   ├── User.java
│   │   │       │   │   ├── Repository.java
│   │   │       │   │   ├── PullRequest.java
│   │   │       │   │   ├── Finding.java
│   │   │       │   │   └── QualityMetric.java
│   │   │       │   ├── dto/
│   │   │       │   │   ├── request/
│   │   │       │   │   └── response/
│   │   │       │   ├── webhook/
│   │   │       │   │   ├── GitHubWebhookEvent.java    # Deserialization classes
│   │   │       │   │   └── HmacVerifier.java
│   │   │       │   └── exception/
│   │   │       │       ├── GlobalExceptionHandler.java
│   │   │       │       └── CodeLensException.java
│   │   │       └── resources/
│   │   │           ├── application.yml
│   │   │           ├── application-dev.yml
│   │   │           └── application-prod.yml
│   │   ├── src/test/
│   │   ├── Dockerfile
│   │   └── pom.xml
│   │
│   ├── ml-worker/                    # FastAPI — Python 3.11
│   │   ├── app/
│   │   │   ├── main.py               # FastAPI app + routes
│   │   │   ├── model.py              # Model load + inference
│   │   │   ├── diff_parser.py        # Unified diff → hunks
│   │   │   ├── tokenizer_utils.py    # Sliding window logic
│   │   │   ├── schemas.py            # Pydantic request/response models
│   │   │   └── config.py             # Settings via pydantic-settings
│   │   ├── training/
│   │   │   ├── dataset.py            # CodeReviewer dataset loader
│   │   │   ├── label_mapper.py       # Comment → taxonomy label
│   │   │   ├── train.py              # Fine-tuning script
│   │   │   └── evaluate.py           # Evaluation + metrics
│   │   ├── tests/
│   │   │   ├── test_api.py
│   │   │   ├── test_diff_parser.py
│   │   │   └── test_model.py
│   │   ├── Dockerfile
│   │   ├── requirements.txt
│   │   └── requirements-train.txt    # Heavier deps only for training
│   │
│   ├── web/                          # Next.js 15 — TypeScript
│   │   ├── src/
│   │   │   ├── app/
│   │   │   │   ├── layout.tsx
│   │   │   │   ├── page.tsx          # Landing
│   │   │   │   ├── dashboard/page.tsx
│   │   │   │   ├── repo/[repoId]/page.tsx
│   │   │   │   ├── pr/[prId]/page.tsx
│   │   │   │   ├── taxonomy/page.tsx
│   │   │   │   ├── settings/page.tsx
│   │   │   │   └── api/auth/[...nextauth]/route.ts
│   │   │   ├── components/
│   │   │   │   ├── ui/               # shadcn/ui components
│   │   │   │   ├── DiffViewer.tsx
│   │   │   │   ├── FindingCard.tsx
│   │   │   │   ├── QualityChart.tsx
│   │   │   │   └── RepoCard.tsx
│   │   │   └── lib/
│   │   │       ├── api.ts            # API client functions
│   │   │       └── types.ts          # Shared TypeScript types
│   │   ├── Dockerfile
│   │   ├── next.config.ts
│   │   └── package.json
│   │
│   └── vscode-ext/                   # VS Code Extension — TypeScript
│       ├── src/
│       │   ├── extension.ts          # Entry point: activate()
│       │   ├── reviewer.ts           # Calls CodeLens API
│       │   ├── decorator.ts          # Inline squiggle + hover
│       │   └── config.ts             # Reads apiKey from settings
│       ├── package.json              # Extension manifest
│       └── tsconfig.json
│
├── github-action/
│   ├── action.yml                    # Action metadata
│   ├── index.js                      # Entry point
│   └── package.json
│
├── infra/
│   ├── docker-compose.yml            # Full local stack
│   ├── docker-compose.dev.yml        # Dev overrides (hot reload)
│   └── nginx/
│       └── nginx.conf                # Local reverse proxy (optional)
│
├── scripts/
│   ├── setup.sh                      # First-time setup
│   ├── seed-db.sql                   # Test data
│   └── download-dataset.sh           # Gets CodeReviewer dataset
│
├── .github/
│   └── workflows/
│       ├── ci-api.yml
│       ├── ci-ml-worker.yml
│       ├── ci-web.yml
│       └── deploy-prod.yml
│
├── .env.example                      # All required env vars documented
├── .gitignore
└── README.md
```

---

## 2. SERVICES AND RESPONSIBILITIES

### `apps/api` — Spring Boot API (Port 8080)
**Owns:** All business logic, user state, GitHub integration, orchestration.
- GitHub OAuth 2.0 login
- Webhook receipt + HMAC-SHA256 verification
- Repo connect/disconnect (installs/removes GitHub webhooks)
- Calls `ml-worker` for inference
- Posts review comment to GitHub PR
- Writes all data to PostgreSQL
- Serves all REST endpoints consumed by `web` and `vscode-ext`
- Issues and validates API keys for VS Code extension
- Does NOT: run ML models, render UI

### `apps/ml-worker` — FastAPI ML Worker (Port 8000)
**Owns:** One thing: take a code diff, return anti-pattern findings.
- Loads fine-tuned CodeBERT on startup
- Parses unified diff into file hunks
- Sliding window tokenization for diffs > 512 tokens
- Runs inference, applies 0.5 sigmoid threshold per label
- Returns structured JSON findings
- Does NOT: talk to GitHub, talk to the DB, handle auth

### `apps/web` — Next.js Dashboard (Port 3000)
**Owns:** The user interface.
- GitHub login (via NextAuth → Spring Boot)
- Dashboard: repo list + quality scores
- PR review detail: diff viewer + inline annotation overlay
- Anti-pattern taxonomy browser
- Settings: connect repos, copy API key
- Does NOT: call GitHub API directly, run models

### `apps/vscode-ext` — VS Code Extension
**Owns:** In-editor experience.
- Reads current open file on save
- Calls Spring Boot `/api/scan/file` with file content + language
- Parses response, renders inline squiggles + hover messages
- User sets `codelens.apiKey` in VS Code settings
- Does NOT: handle OAuth, talk to DB

### `github-action/` — GitHub Action
**Owns:** CI/CD integration entry point.
- Triggered on `pull_request` events
- Calls Spring Boot `/api/scan/action` with the diff from `GITHUB_TOKEN`
- Posts findings as a PR check-run annotation
- Does NOT: run the model itself

---

## 3. TECH STACK — FINAL DECISIONS

No alternatives listed. These are the decisions.

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

**What we are NOT using:**
- Kafka / RabbitMQ (overkill for MVP — use `@Async`)
- GraphQL (REST is sufficient)
- Kubernetes (Railway handles orchestration)
- Elasticsearch (no full-text search needed)
- Redis for session storage (JWT is stateless)

---

## 4. API CONTRACT

All Spring Boot endpoints. FastAPI is internal (not called by frontend directly).

### Authentication
All protected endpoints require: `Authorization: Bearer <jwt_token>`
VS Code extension uses: `Authorization: Bearer <api_key>` (same header, different validation path)

**Error format (all endpoints):**
```json
{
  "error": "REPO_NOT_FOUND",
  "message": "Repository with id abc123 not found",
  "timestamp": "2026-06-19T10:30:00Z"
}
```

---

### Auth Endpoints

**`GET /api/auth/github`**
Redirects user to GitHub OAuth. No body. No auth required.

**`GET /api/auth/callback?code=xxx`**
GitHub redirects here. Exchanges code for GitHub token, creates/updates user, returns JWT.
```json
// Response 200
{
  "accessToken": "eyJhbGc...",
  "expiresIn": 3600,
  "user": {
    "id": "uuid",
    "githubUsername": "tanmay-alpha",
    "avatarUrl": "https://avatars.githubusercontent.com/..."
  }
}
```

**`POST /api/auth/refresh`**
```json
// Request
{ "refreshToken": "eyJhbGc..." }

// Response 200
{ "accessToken": "eyJhbGc...", "expiresIn": 3600 }
```

**`GET /api/auth/me`** — Auth required
```json
// Response 200
{
  "id": "uuid",
  "githubUsername": "tanmay-alpha",
  "avatarUrl": "https://...",
  "apiKey": "cl_live_xxxxxxxxxxxxx",   // for VS Code extension
  "createdAt": "2026-01-01T00:00:00Z"
}
```

**`POST /api/auth/api-key/regenerate`** — Auth required
Returns new API key, invalidates old one.

---

### Repository Endpoints

**`GET /api/repos`** — Auth required
```json
// Response 200
[
  {
    "id": "uuid",
    "fullName": "tanmay-alpha/MAET",
    "description": "My algo trading platform",
    "isPrivate": false,
    "webhookActive": true,
    "qualityScore": 74.2,
    "lastReviewedAt": "2026-06-18T09:00:00Z"
  }
]
```

**`POST /api/repos/connect`** — Auth required
```json
// Request
{ "githubRepoFullName": "tanmay-alpha/MAET" }

// Response 201
{
  "id": "uuid",
  "fullName": "tanmay-alpha/MAET",
  "webhookId": 123456789,
  "webhookActive": true
}
```

**`DELETE /api/repos/{repoId}`** — Auth required
Removes webhook from GitHub, marks repo inactive. Response 204.

**`GET /api/repos/{repoId}/prs`** — Auth required
```json
// Response 200
{
  "data": [
    {
      "id": "uuid",
      "prNumber": 42,
      "title": "Add user auth module",
      "author": "tanmay-alpha",
      "status": "reviewed",
      "qualityScore": 68.5,
      "findingsCount": { "critical": 1, "major": 2, "minor": 3 },
      "reviewedAt": "2026-06-18T09:00:00Z"
    }
  ],
  "total": 47,
  "page": 1,
  "pageSize": 20
}
```

---

### Review Endpoints

**`GET /api/reviews/{prId}`** — Auth required
```json
// Response 200
{
  "id": "uuid",
  "prNumber": 42,
  "title": "Add user auth module",
  "qualityScore": 68.5,
  "githubPrUrl": "https://github.com/tanmay-alpha/MAET/pull/42",
  "findings": [
    {
      "id": "uuid",
      "filePath": "src/auth/service.py",
      "lineStart": 23,
      "lineEnd": 31,
      "antiPattern": "PERFORMANCE_N_PLUS_1",
      "category": "PERFORMANCE",
      "severity": "major",
      "confidence": 0.91,
      "explanation": "Database query inside a loop detected. Move query outside the loop or use bulk fetch."
    }
  ],
  "reviewedAt": "2026-06-18T09:00:00Z"
}
```

**`POST /api/scan/file`** — API Key auth (VS Code extension)
```json
// Request
{
  "content": "def get_users():\n    for u in users:\n        db.query(...)",
  "language": "python",
  "filePath": "src/service.py"
}

// Response 200
{
  "findings": [
    {
      "lineStart": 3,
      "lineEnd": 4,
      "antiPattern": "PERFORMANCE_N_PLUS_1",
      "category": "PERFORMANCE",
      "severity": "major",
      "confidence": 0.88,
      "explanation": "Database query inside a loop."
    }
  ],
  "qualityScore": 72.0
}
```

**`POST /api/scan/action`** — GitHub Action endpoint (GITHUB_TOKEN validated)
```json
// Request
{
  "diff": "unified diff string here",
  "repoFullName": "tanmay-alpha/MAET",
  "prNumber": 42,
  "language": "python"
}

// Response 200
{
  "findings": [...],
  "qualityScore": 68.5,
  "postedToGitHub": true
}
```

---

### Webhook Endpoint

**`POST /api/webhook/github`** — No user auth (HMAC verified)
Headers required: `X-Hub-Signature-256`, `X-GitHub-Event`
```json
// GitHub sends this (pull_request event, action: opened/synchronize)
{
  "action": "opened",
  "pull_request": {
    "number": 42,
    "title": "Add user auth module",
    "user": { "login": "tanmay-alpha" },
    "head": { "sha": "abc123" },
    "diff_url": "https://github.com/.../42.diff"
  },
  "repository": {
    "id": 789,
    "full_name": "tanmay-alpha/MAET"
  }
}

// Response: always 200 immediately (processing is @Async)
{ "status": "accepted" }
```

---

### Metrics Endpoint

**`GET /api/metrics/quality-trend?repoId={id}&days=30`** — Auth required
```json
// Response 200
{
  "repoId": "uuid",
  "repoName": "tanmay-alpha/MAET",
  "period": "30d",
  "data": [
    { "date": "2026-05-20", "avgQuality": 61.2, "prsReviewed": 3 },
    { "date": "2026-05-21", "avgQuality": 65.0, "prsReviewed": 1 }
  ],
  "summary": {
    "currentScore": 74.2,
    "trend": "+8.5",
    "totalPrsReviewed": 47,
    "topAntiPattern": "PERFORMANCE_N_PLUS_1"
  }
}
```

---

### FastAPI ML Worker Contract (internal only)

**`POST /ml/review`**
```json
// Request
{
  "diff": "unified diff or file content string",
  "language": "python",
  "mode": "diff"   // "diff" | "file"
}

// Response 200
{
  "findings": [
    {
      "lineStart": 23,
      "lineEnd": 31,
      "antiPattern": "PERFORMANCE_N_PLUS_1",
      "category": "PERFORMANCE",
      "severity": "major",
      "confidence": 0.91,
      "explanation": "Database query inside a loop."
    }
  ],
  "qualityScore": 68.5,
  "processingTimeMs": 187,
  "windowsProcessed": 1
}

// Response 422 (invalid input)
{ "detail": "diff must not be empty" }
```

**`GET /ml/health`**
```json
// Response 200
{
  "status": "ok",
  "modelLoaded": true,
  "modelName": "tanmay-alpha/codelens-codebert",
  "device": "cpu"
}
```

---

## 5. DATABASE SCHEMA

Run order matters. Execute in this sequence.

```sql
-- ============================================
-- Extensions
-- ============================================
CREATE EXTENSION IF NOT EXISTS "pgcrypto";   -- for gen_random_uuid()


-- ============================================
-- USERS
-- ============================================
CREATE TABLE users (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    github_id        BIGINT      UNIQUE NOT NULL,
    github_username  VARCHAR(100) NOT NULL,
    avatar_url       TEXT,
    access_token     TEXT        NOT NULL,          -- GitHub OAuth token, AES-encrypted
    refresh_token    TEXT,                           -- JWT refresh token, bcrypt-hashed
    api_key_hash     VARCHAR(100),                   -- bcrypt hash of VS Code API key
    api_key_prefix   VARCHAR(10),                    -- "cl_live_xxx" prefix (for display)
    created_at       TIMESTAMPTZ DEFAULT NOW(),
    updated_at       TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_users_github_id ON users(github_id);


-- ============================================
-- REPOSITORIES
-- ============================================
CREATE TABLE repositories (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    github_id        BIGINT      UNIQUE NOT NULL,
    full_name        VARCHAR(255) NOT NULL,           -- "tanmay-alpha/MAET"
    description      TEXT,
    is_private       BOOLEAN     DEFAULT FALSE,
    owner_id         UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    webhook_id       BIGINT,                          -- GitHub webhook ID
    webhook_secret   TEXT,                            -- HMAC secret (encrypted)
    is_active        BOOLEAN     DEFAULT TRUE,
    quality_score    DECIMAL(5,2),                    -- Latest rolling score
    created_at       TIMESTAMPTZ DEFAULT NOW(),
    updated_at       TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_repos_owner_id ON repositories(owner_id);
CREATE INDEX idx_repos_github_id ON repositories(github_id);


-- ============================================
-- PULL REQUESTS
-- ============================================
CREATE TABLE pull_requests (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    github_pr_number    INT         NOT NULL,
    repo_id             UUID        NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    title               TEXT,
    author_github       VARCHAR(100),
    head_sha            VARCHAR(40),
    github_pr_url       TEXT,
    status              VARCHAR(20) NOT NULL DEFAULT 'pending',
                        -- pending | processing | reviewed | failed
    quality_score       DECIMAL(5,2),
    github_comment_id   BIGINT,
    error_message       TEXT,                        -- If status = failed
    reviewed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ DEFAULT NOW(),

    CONSTRAINT uq_pr_repo UNIQUE (repo_id, github_pr_number)
);

CREATE INDEX idx_prs_repo_id ON pull_requests(repo_id);
CREATE INDEX idx_prs_status  ON pull_requests(status);


-- ============================================
-- FINDINGS
-- ============================================
CREATE TABLE findings (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    pr_id            UUID        NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE,
    file_path        TEXT        NOT NULL,
    line_start       INT,
    line_end         INT,
    anti_pattern     VARCHAR(80) NOT NULL,
                     -- e.g. PERFORMANCE_N_PLUS_1, SECURITY_HARDCODED_SECRET
    category         VARCHAR(30) NOT NULL,
                     -- SECURITY | PERFORMANCE | ARCHITECTURE | RELIABILITY | READABILITY | MAINTAINABILITY
    severity         VARCHAR(10) NOT NULL,
                     -- critical | major | minor
    confidence       DECIMAL(4,3) NOT NULL,           -- 0.000 to 1.000
    explanation      TEXT,
    code_snippet     TEXT,                            -- Flagged lines (max 500 chars)
    created_at       TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_findings_pr_id   ON findings(pr_id);
CREATE INDEX idx_findings_category ON findings(category);


-- ============================================
-- QUALITY METRICS (pre-aggregated for charts)
-- ============================================
CREATE TABLE quality_metrics (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    repo_id          UUID        NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    date             DATE        NOT NULL,
    avg_quality      DECIMAL(5,2),
    prs_reviewed     INT         DEFAULT 0,
    critical_count   INT         DEFAULT 0,
    major_count      INT         DEFAULT 0,
    minor_count      INT         DEFAULT 0,

    CONSTRAINT uq_metric_repo_date UNIQUE (repo_id, date)
);

CREATE INDEX idx_metrics_repo_date ON quality_metrics(repo_id, date DESC);


-- ============================================
-- WEBHOOK DEDUP (prevents double-processing)
-- ============================================
CREATE TABLE processed_webhooks (
    delivery_id      VARCHAR(100) PRIMARY KEY,       -- X-GitHub-Delivery header
    processed_at     TIMESTAMPTZ  DEFAULT NOW()
);

-- Auto-delete entries older than 24 hours (run via pg_cron or app scheduler)
-- In MVP: just check before processing, skip if found
```

---

## 6. DATA PIPELINE (ML Training Data)

This runs **once** before training. Result is saved as files in `ml-worker/training/data/`.

### Step 1: Download
```bash
# scripts/download-dataset.sh
git clone https://github.com/microsoft/CodeBERT.git /tmp/codebert
cp -r /tmp/codebert/CodeReviewer/code-review-data ./apps/ml-worker/training/data/raw/
```

Raw data structure: `{diff: string, comment: string, label: 0|1}` — 1 means comment is actionable.

### Step 2: Filter
File: `training/dataset.py`

Rules for dropping a sample:
- `comment` length < 20 characters
- `comment` contains only: "nit", "typo", "rename", "spacing", "whitespace", "format"
- `label == 0` (non-actionable comments)
- `diff` is empty

Expected yield: ~55,000 samples from ~150,000 raw.

### Step 3: Label Mapping
File: `training/label_mapper.py`

```python
KEYWORD_MAP = {
    "SECURITY": ["password", "secret", "api_key", "token", "credential",
                 "sql injection", "eval(", "hardcoded", "plaintext"],
    "PERFORMANCE": ["n+1", "loop", "query inside", "redundant", "unnecessary iteration",
                    "O(n^2)", "nested loop", "repeated call"],
    "ARCHITECTURE": ["god class", "too many responsibilities", "single responsibility",
                     "circular", "feature envy", "coupling"],
    "RELIABILITY": ["bare except", "except:", "null check", "none check",
                    "not handled", "resource leak", "unclosed"],
    "READABILITY": ["magic number", "unclear", "misleading", "confusing name"],
    "MAINTAINABILITY": ["too long", "deep nesting", "duplicate", "copy-paste"],
}
```

Process:
1. Keyword match assigns primary category
2. One sample can get multiple category labels (multi-label)
3. Samples that match zero categories → discarded
4. Output: `{diff, labels: [0,0,1,0,0,1]}` (6-dimensional binary vector)

### Step 4: Manual Verification
Open `training/data/verify_sample.py` — shows 500 random samples with their assigned labels.
You approve or reject each one. Aim for 90%+ agreement.

### Step 5: Split
Split by **PR ID** (not randomly) to prevent leakage.
- Train: 80% of PRs
- Val: 10% of PRs
- Test: 10% of PRs (never touch until final eval)

Output files:
```
training/data/
├── raw/              # Downloaded CodeReviewer data
├── filtered/         # After step 2
├── labeled/          # After step 3 (with taxonomy labels)
├── train.json        # Step 5 output
├── val.json
└── test.json         # NEVER open during development
```

---

## 7. ML PIPELINE

All files in `apps/ml-worker/training/`.

### Environment
```bash
# requirements-train.txt (heavier — only for training, not inference)
torch==2.3.0
transformers==4.41.0
datasets==2.19.0
scikit-learn==1.5.0
numpy==1.26.4
pandas==2.2.2
accelerate==0.30.0
evaluate==0.4.2
```

### Training Script — `train.py`

```python
# Key config — do not change without documenting why
MODEL_NAME = "microsoft/codebert-base"
NUM_LABELS = 6                    # One per category
MAX_SEQ_LENGTH = 512
LEARNING_RATE = 2e-5
BATCH_SIZE = 16                   # On Colab T4: fits 16
NUM_EPOCHS = 5
WEIGHT_DECAY = 0.01
WARMUP_RATIO = 0.1
THRESHOLD = 0.5                   # Sigmoid threshold per label
HF_REPO = "tanmay-alpha/codelens-codebert"
```

**Loss function:** `BCEWithLogitsLoss` — binary cross-entropy per label independently.

**Training environment:** Google Colab Pro (T4 GPU). Save checkpoint after each epoch. Push best model (by val macro-F1) to HuggingFace Hub private repo.

### Evaluation — `evaluate.py`

Report per label:
- Precision, Recall, F1
- Macro-averaged F1 (main metric)
- Baseline 1: keyword matching (from label_mapper.py applied directly)
- Baseline 2: GPT-4o zero-shot with a structured prompt (run manually, save results)

### Sliding Window (inference only) — `tokenizer_utils.py`

```python
def sliding_window_tokenize(text, tokenizer, max_length=512, stride=50):
    """
    Returns list of token windows for texts > max_length tokens.
    Max-pool logits across windows after inference.
    """
```

### Model Serving — `model.py`

```python
# Loaded once at FastAPI startup, reused for all requests
class CodeLensModel:
    def __init__(self):
        self.tokenizer = AutoTokenizer.from_pretrained(HF_REPO)
        self.model = AutoModelForSequenceClassification.from_pretrained(HF_REPO)
        self.model.eval()

    def predict(self, text: str) -> list[dict]:
        # Returns list of findings with confidence per label
```

---

## 8. AUTH / SECURITY APPROACH

### User Authentication Flow
```
1. User clicks "Login with GitHub" on Next.js
2. Next.js redirects to /api/auth/github (Spring Boot)
3. Spring Boot redirects to GitHub OAuth
4. GitHub redirects back to /api/auth/callback?code=xxx
5. Spring Boot exchanges code for GitHub access token
6. Creates/updates user in DB (stores GitHub token encrypted)
7. Issues JWT access token (15 min TTL) + refresh token (7 days TTL)
8. Redirects to Next.js dashboard with tokens in httpOnly cookies
```

### JWT Implementation
```java
// Access token payload
{
  "sub": "user-uuid",
  "githubUsername": "tanmay-alpha",
  "iat": 1234567890,
  "exp": 1234568790   // 15 minutes
}
```
Library: `io.jsonwebtoken:jjwt` (Spring Boot)

Tokens stored: httpOnly cookies (not localStorage — prevents XSS).

### API Key (VS Code Extension)
- Generated on first login, stored as bcrypt hash in DB
- Format: `cl_live_` + 32 random hex chars
- Prefix stored plaintext for display: user sees `cl_live_abc...xyz` (truncated)
- Sent as `Authorization: Bearer cl_live_xxxxx`
- Validated by looking up `api_key_prefix` + bcrypt verify

### Webhook Security
```java
// HmacVerifier.java
public boolean verify(String payload, String signatureHeader, String secret) {
    // signatureHeader = "sha256=abc123..."
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
    String computed = "sha256=" + Hex.encode(mac.doFinal(payload.getBytes()));
    return MessageDigest.isEqual(computed.getBytes(), signatureHeader.getBytes());
    // MessageDigest.isEqual prevents timing attacks
}
```

Each connected repo gets its own random webhook secret (stored encrypted in DB).

### Data Encryption
- GitHub OAuth tokens: AES-256-GCM encrypted before DB write (Spring's `@Convert`)
- Webhook secrets: same
- Key management: single `ENCRYPTION_KEY` env var (32 bytes, base64)

### FastAPI Worker Security
- Not exposed to internet in production (internal-only service)
- Called from Spring Boot only
- Uses a shared internal secret: `ML_WORKER_SECRET` header
- Spring Boot sends it; FastAPI validates it

### Rate Limiting
- `/api/scan/file` (VS Code): 60 requests/minute per API key (Redis counter)
- Webhook endpoint: no limit (GitHub controls frequency)
- Auth endpoints: 10 requests/minute per IP

---

## 9. DOCKER SETUP

### `docker-compose.yml` (local development — full stack)

```yaml
version: '3.9'

services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: codelens
      POSTGRES_USER: codelens
      POSTGRES_PASSWORD: codelens_local
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./scripts/seed-db.sql:/docker-entrypoint-initdb.d/seed.sql
    healthcheck:
      test: ["CMD", "pg_isready", "-U", "codelens"]
      interval: 5s
      timeout: 3s
      retries: 5

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

  ml-worker:
    build:
      context: ./apps/ml-worker
      dockerfile: Dockerfile
    environment:
      - MODEL_NAME=${ML_MODEL_NAME}
      - HF_TOKEN=${HF_TOKEN}
      - ML_WORKER_SECRET=${ML_WORKER_SECRET}
    ports:
      - "8000:8000"
    # NOTE: First startup is slow — model downloads ~500MB from HuggingFace
    # After first run, model is cached in Docker volume
    volumes:
      - hf_cache:/root/.cache/huggingface

  api:
    build:
      context: ./apps/api
      dockerfile: Dockerfile
    environment:
      - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/codelens
      - SPRING_DATASOURCE_USERNAME=codelens
      - SPRING_DATASOURCE_PASSWORD=codelens_local
      - SPRING_REDIS_HOST=redis
      - GITHUB_CLIENT_ID=${GITHUB_CLIENT_ID}
      - GITHUB_CLIENT_SECRET=${GITHUB_CLIENT_SECRET}
      - JWT_SECRET=${JWT_SECRET}
      - ENCRYPTION_KEY=${ENCRYPTION_KEY}
      - ML_WORKER_URL=http://ml-worker:8000
      - ML_WORKER_SECRET=${ML_WORKER_SECRET}
    ports:
      - "8080:8080"
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_started
      ml-worker:
        condition: service_started

  web:
    build:
      context: ./apps/web
      dockerfile: Dockerfile
    environment:
      - NEXT_PUBLIC_API_URL=http://localhost:8080
      - NEXTAUTH_URL=http://localhost:3000
      - NEXTAUTH_SECRET=${NEXTAUTH_SECRET}
    ports:
      - "3000:3000"
    depends_on:
      - api

volumes:
  postgres_data:
  hf_cache:
```

### `apps/ml-worker/Dockerfile`
```dockerfile
FROM python:3.11-slim

WORKDIR /app

# Install deps first (cache layer)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app/ ./app/

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
```

### `apps/api/Dockerfile`
```dockerfile
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/codelens-api.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

---

## 10. LOCAL DEVELOPMENT SETUP

### Prerequisites
```
- Java 21 (use SDKMAN: sdk install java 21.0.3-tem)
- Python 3.11 (use pyenv)
- Node.js 20+ (use nvm)
- Maven 3.9
- Docker Desktop
- VS Code
```

### First-Time Setup
```bash
# 1. Clone
git clone https://github.com/tanmay-alpha/codelens.git
cd codelens

# 2. Copy env vars and fill in your values
cp .env.example .env
# Fill: GITHUB_CLIENT_ID, GITHUB_CLIENT_SECRET, JWT_SECRET, HF_TOKEN

# 3. Start infrastructure only (DB + Redis) — not the full app yet
docker compose up postgres redis -d

# 4. Start ML worker separately (first start is slow — model download)
docker compose up ml-worker -d
# Watch logs: docker compose logs -f ml-worker
# Wait for: "Model loaded successfully"

# 5. Run Spring Boot locally (faster iteration than Docker)
cd apps/api
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 6. Run Next.js locally
cd apps/web
npm install
npm run dev

# 7. Verify everything works
curl http://localhost:8000/ml/health  # ML worker
curl http://localhost:8080/actuator/health  # Spring Boot
# Open http://localhost:3000  # Next.js
```

### `.env.example` (every var documented)
```bash
# GitHub OAuth App (create at github.com/settings/developers)
GITHUB_CLIENT_ID=your_client_id
GITHUB_CLIENT_SECRET=your_client_secret

# JWT signing key (generate: openssl rand -base64 64)
JWT_SECRET=your_64_char_base64_secret

# AES encryption key for DB fields (generate: openssl rand -base64 32)
ENCRYPTION_KEY=your_32_char_base64_key

# HuggingFace token (for private model access)
HF_TOKEN=hf_xxxxxxxxxxxxx

# ML model name on HuggingFace Hub
ML_MODEL_NAME=tanmay-alpha/codelens-codebert

# Internal secret between Spring Boot and FastAPI
ML_WORKER_SECRET=your_random_32_char_secret

# FastAPI URL (used by Spring Boot)
ML_WORKER_URL=http://localhost:8000

# Next.js
NEXTAUTH_SECRET=your_nextauth_secret
NEXT_PUBLIC_API_URL=http://localhost:8080

# Database (for local non-Docker dev)
DATABASE_URL=postgresql://codelens:codelens_local@localhost:5432/codelens
```

### Daily Development Workflow
```bash
# Terminal 1: Infrastructure
docker compose up postgres redis ml-worker -d

# Terminal 2: Spring Boot (hot reload with DevTools)
cd apps/api && mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Terminal 3: Next.js
cd apps/web && npm run dev
```

---

## 11. TESTING PLAN

### ML Worker Tests (`apps/ml-worker/tests/`)

**`test_diff_parser.py`** — Pure unit tests, no model needed
```python
def test_parses_added_lines():
    diff = "+def bad_function():\n+    for u in users:\n+        db.query(u.id)"
    hunks = parse_diff(diff)
    assert len(hunks) == 1
    assert hunks[0].language is None  # parser doesn't infer language

def test_empty_diff_raises():
    with pytest.raises(ValueError):
        parse_diff("")
```

**`test_model.py`** — Integration, requires model loaded
```python
# Mark as slow: @pytest.mark.slow
def test_n_plus_1_detected():
    diff = "+for user in users:\n+    result = db.execute(f'SELECT * FROM orders WHERE id={user.id}')"
    response = client.post("/ml/review", json={"diff": diff, "language": "python", "mode": "diff"})
    assert response.status_code == 200
    categories = [f["category"] for f in response.json()["findings"]]
    assert "PERFORMANCE" in categories

def test_clean_code_no_findings():
    diff = "+def add(a, b):\n+    return a + b"
    response = client.post("/ml/review", json={"diff": diff, "language": "python", "mode": "diff"})
    assert response.status_code == 200
    # Clean code may still have findings but quality score should be high
    assert response.json()["qualityScore"] > 80
```

### Spring Boot Tests (`apps/api/src/test/`)

**Unit Tests (JUnit 5 + Mockito)**
- `HmacVerifierTest` — Valid signature passes, forged signature fails, timing-safe
- `ReviewServiceTest` — Mock MlWorkerService, verify DB writes happen after review
- `GitHubServiceTest` — Mock WebClient, verify correct API calls

**Integration Tests (Spring Boot Test + Testcontainers)**
```java
@SpringBootTest
@Testcontainers
class WebhookControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Test
    void validWebhookIsAccepted() throws Exception {
        String payload = loadFile("test-data/pr-opened-event.json");
        String signature = computeHmac(payload, "test-secret");

        mockMvc.perform(post("/api/webhook/github")
            .header("X-Hub-Signature-256", signature)
            .header("X-GitHub-Event", "pull_request")
            .content(payload)
            .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());
    }

    @Test
    void forgedWebhookIsRejected() throws Exception {
        mockMvc.perform(post("/api/webhook/github")
            .header("X-Hub-Signature-256", "sha256=invalid")
            .header("X-GitHub-Event", "pull_request")
            .content("{}")
            .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized());
    }
}
```

### Next.js Tests (`apps/web/`)
- `DiffViewer.test.tsx` — Renders diff correctly, finding overlays appear at right lines
- `api.test.ts` — API client functions return correct types (mock fetch)

### Test Markers
```
@pytest.mark.unit    — No external deps, fast (<1s each)
@pytest.mark.slow    — Requires model loaded (skip in CI unless on ML runner)
@pytest.mark.integration — Requires DB/Redis
```

### What We Are NOT Testing
- Fine-tuning itself (you verify this via evaluation metrics)
- GitHub API responses (mock them)
- Next.js page rendering end-to-end (too slow for MVP, add in v2)

---

## 12. CI/CD PLAN

### `.github/workflows/ci-api.yml`
Triggers: push to `main`, PR to `main`
```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:16
        env: { POSTGRES_DB: codelens_test, ... }
    steps:
      - checkout
      - setup Java 21
      - mvn test                        # Unit + integration tests
      - upload test report artifact
```

### `.github/workflows/ci-ml-worker.yml`
Triggers: push/PR touching `apps/ml-worker/**`
```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - checkout
      - setup Python 3.11
      - pip install -r requirements.txt
      - pytest -m "not slow"            # Unit tests only (no model in CI)
      - ruff check .                    # Linting
      - mypy app/                       # Type checking
```

### `.github/workflows/ci-web.yml`
Triggers: push/PR touching `apps/web/**`
```yaml
steps:
  - npm ci
  - npm run type-check                  # tsc --noEmit
  - npm run lint                        # ESLint
  - npm test                            # Component tests
```

### `.github/workflows/deploy-prod.yml`
Triggers: push to `main` after all CI passes
```yaml
jobs:
  deploy-api:
    # Railway deploy via railway CLI or webhook trigger

  deploy-ml-worker:
    # HuggingFace Inference Endpoint redeploy via API

  deploy-web:
    # Vercel auto-deploys on push to main (configured in Vercel dashboard)
```

### Branch Strategy
```
main         — production, protected (requires PR + passing CI)
dev          — integration branch
feat/*       — feature branches (one per GitHub issue)
```

One rule: **never commit directly to `main`.** Every change is a PR, even solo.

---

## 13. DEPLOYMENT ARCHITECTURE

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

**Cost at MVP:**
- Railway: ~$10/month (API + DB + Redis)
- HuggingFace Inference Endpoints: first 8 CPU-hours/month free
- Vercel: free (hobby tier)
- Total: **~$10/month**

**Environment variables in production:**
Set in Railway dashboard (not in `.env` files — those are `.gitignore`d).

---

## 14. FIRST 20 GITHUB ISSUES

### Milestone 1: ML Foundation (Week 1–2)

**Issue #1: Download and inspect CodeReviewer dataset**
> Download raw CodeReviewer dataset from Microsoft/CodeBERT repo. Run `dataset.py` to count samples, inspect 20 random examples, document actual distribution of comment lengths and types.
> AC: `training/data/raw/` populated. `dataset_stats.md` with counts and 5 example samples.

**Issue #2: Implement label mapper with taxonomy**
> Write `label_mapper.py` with keyword mapping for all 6 categories. Run on full dataset. Print label distribution (how many samples per category).
> AC: `label_mapper.py` maps comments to 6-category multi-label vectors. Distribution logged. ~40–60K samples survive filtering.

**Issue #3: Manual verification of 500 samples**
> Build `verify_sample.py` interactive script. Manually approve/reject labels for 500 random samples. Document inter-rater agreement (yourself vs keyword match).
> AC: `verification_report.md` with agreement %, common misclassifications, and any taxonomy refinements needed.

**Issue #4: Train/val/test split by PR ID**
> Split dataset by PR ID (not row) to prevent leakage. Save `train.json`, `val.json`, `test.json`. Log sample counts per split per category.
> AC: Three JSON files. `test.json` committed to repo but not to be touched until final eval.

**Issue #5: Implement CodeBERT fine-tuning pipeline**
> Write `train.py` with full training loop. Train on Colab T4. Save best checkpoint by val macro-F1 to HuggingFace Hub private repo.
> AC: Model pushed to `tanmay-alpha/codelens-codebert`. Val macro-F1 >= 0.68.

**Issue #6: Evaluate model against two baselines**
> Run `evaluate.py` on held-out test set. Run keyword-matching baseline on same set. Run GPT-4o zero-shot on 200 test samples (cost: ~$2). Write comparison table.
> AC: `evaluation_results.md` with full F1 table. Fine-tuned model beats both baselines on macro-F1.

---

### Milestone 2: ML Worker Service (Week 3)

**Issue #7: Implement unified diff parser**
> Write `diff_parser.py`. Handles: +/- lines, @@ headers, binary files (skip), multiple files in one diff.
> AC: `test_diff_parser.py` with 8 test cases all passing.

**Issue #8: Implement sliding window tokenizer**
> Write `tokenizer_utils.py`. Windows of 512 tokens, 50-token stride. Max-pool logits across windows.
> AC: Unit test with a 1500-token diff processes correctly. Output is same shape as single-window output.

**Issue #9: Build FastAPI ML worker with /ml/review endpoint**
> Wire together parser + tokenizer + model. Add `/ml/review` and `/ml/health`. Add Pydantic schemas for request/response.
> AC: `curl` test with sample diff returns structured findings JSON. Docker container starts and loads model cleanly.

---

### Milestone 3: Spring Boot API (Week 3–4)

**Issue #10: Spring Boot project scaffold + DB entities**
> Init Spring Boot 3.3 with: JPA, Security, WebClient, Actuator, Flyway (migrations). Create all 5 JPA entities. Write `V1__initial_schema.sql` Flyway migration.
> AC: `mvn spring-boot:run` connects to local Postgres, creates all tables, Actuator `/health` returns UP.

**Issue #11: GitHub OAuth 2.0 login + JWT issuance**
> Implement `/api/auth/github` → redirect → `/api/auth/callback` → issue JWT + refresh token. Store user in DB.
> AC: Full OAuth flow works end-to-end. JWT returned in httpOnly cookie. `/api/auth/me` returns user data when authenticated.

**Issue #12: Webhook receiver with HMAC verification**
> Implement `/api/webhook/github`. Verify `X-Hub-Signature-256`. Deduplicate via `processed_webhooks` table. Trigger `@Async` processing.
> AC: Valid webhook returns 200 immediately. Forged webhook returns 401. Duplicate delivery ID is ignored.

**Issue #13: Full review flow — webhook to GitHub comment**
> `WebhookService.processAsync()`: fetch diff from GitHub → call FastAPI → save findings to DB → post GitHub comment.
> AC: Open a real PR on a test repo. Within 10 seconds, CodeLens comment appears with findings.

**Issue #14: Repo management endpoints + webhook installation**
> `POST /api/repos/connect`: call GitHub API to install webhook on target repo. `DELETE /api/repos/{id}`: remove webhook.
> AC: Connecting a repo installs webhook visible in GitHub repo settings. Disconnecting removes it.

**Issue #15: VS Code API key generation and validation**
> `POST /api/auth/api-key/regenerate`. Validate `cl_live_xxx` keys on `/api/scan/file`. Rate limit: 60 req/min per key.
> AC: Key generated, stored as bcrypt hash, validated correctly, rate limit enforced.

---

### Milestone 4: Frontend + Extensions (Week 5–7)

**Issue #16: Next.js auth + dashboard shell**
> GitHub login → JWT stored in cookie → dashboard with repo list. `useAuth` hook. Layout with sidebar nav.
> AC: Login → dashboard flow works. Connected repos shown. Disconnected users redirected to login.

**Issue #17: PR review page with diff viewer + annotations**
> Fetch PR review data from API. Render split diff view. Overlay finding annotations at correct line numbers.
> AC: Open any reviewed PR in dashboard. Findings appear inline with correct line positions and severity colors.

**Issue #18: Quality trend chart + repo detail page**
> Recharts line chart of quality score over time. Per-repo stats: top anti-patterns, PRs reviewed count.
> AC: Chart renders 30 days of data. Hover shows date + score + PR count.

**Issue #19: VS Code extension — file scan on save**
> Extension reads current file on `onDidSaveTextDocument`. Calls `/api/scan/file` with API key from settings. Shows findings as diagnostic squiggles.
> AC: Save a Python file with a known N+1 loop → squiggle appears on the correct line → hover shows explanation.

**Issue #20: GitHub Actions integration**
> `action.yml` + `index.js`. On PR open: fetch diff, call `/api/scan/action`, post findings as PR check annotations.
> AC: Add action to a test repo. Open a PR with bad code. Findings appear as GitHub check annotations in the Files changed tab.

---

## 15. EXACT FIRST IMPLEMENTATION ORDER

This is the order you work in. Do not skip ahead. Each step unblocks the next.

```
Day 1–2:
  Issue #1 — Download dataset, look at it
  Issue #2 — Label mapper (no model needed, pure Python)
  Issue #3 — Manual verification (boring but critical)

Day 3–4:
  Issue #4 — Train/val/test split
  Issue #5 — First training run on Colab (START THIS EARLY — takes hours)
              While model trains → start Issue #7

Day 5–7:
  Issue #6 — Evaluation (once training finishes)
  Issue #7 — Diff parser (can be done in parallel with training)
  Issue #8 — Sliding window tokenizer

Day 8–9:
  Issue #9 — FastAPI worker wrapping the trained model
              THIS IS YOUR FIRST WORKING DEMO. Show it with curl.

Day 10–12:
  Issue #10 — Spring Boot scaffold + DB entities
  Issue #11 — GitHub OAuth (you need this before anything else in Spring Boot)

Day 13–15:
  Issue #12 — Webhook receiver
  Issue #13 — Full review flow (THIS IS YOUR SECOND DEMO — real PR gets reviewed)

Day 16–18:
  Issue #14 — Repo connect/disconnect
  Issue #15 — API key system

Day 19–23:
  Issue #16 — Next.js auth + dashboard
  Issue #17 — PR review page with diff viewer (most frontend work is here)

Day 24–27:
  Issue #18 — Quality chart
  Issue #19 — VS Code extension

Day 28–30:
  Issue #20 — GitHub Actions
  Deploy everything
  Record demo video
  Write README

Buffer: 2 days for debugging integration issues (always needed).
```

### The Two Demos to Hit Before You Write Another Line
1. **Demo 1 (Day 9):** `curl -X POST http://localhost:8000/ml/review -d '{"diff":"..."}'` returns real anti-pattern findings. This proves the ML core works.
2. **Demo 2 (Day 15):** Open a PR on your test GitHub repo. Wait 5 seconds. CodeLens comment appears. This proves the end-to-end flow works.

Everything after Day 15 is polish and UI. The hard problems are solved.

---

*This document is the source of truth for all implementation decisions. Any deviation requires a note explaining why. Last updated: pre-implementation (before first commit).*
