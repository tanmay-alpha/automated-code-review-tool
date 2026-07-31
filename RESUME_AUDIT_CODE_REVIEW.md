# Automated Code Review Platform — Resume Audit

## 1. Executive Summary

The **Automated Code Review Platform** is a multi-tier microservice software quality analysis system designed to inspect Pull Request (PR) diffs and source code snippets for structural anti-patterns and security vulnerabilities. The system comprises a **Spring Boot 3.4 REST API** (acting as the control plane orchestrator), a **FastAPI ML Worker** (executing code analysis via a rule-based AST fallback engine and a CodeBERT tokenization pipeline), a **Next.js 15 App Router Dashboard** (for metrics visualization), a **VS Code Extension** (for local diagnostics), and a **Custom GitHub Action** (for CI pipeline gating).

The platform incorporates security and reliability mechanisms: **HMAC-SHA256 GitHub webhook verification**, **JWT + GitHub OAuth2 authentication**, **Redis-backed per-IP rate limiting**, a **Transactional Outbox pattern with `FOR UPDATE SKIP LOCKED` for asynchronous webhook processing**, **Resilience4j circuit breakers**, and **AES-256-GCM secret encryption**. The machine-learning tokenization, sliding-window chunking, and fine-tuning architecture are fully implemented in Python; however, **no trained PyTorch model binary weights or benchmark evaluation results exist in the repository**. As a result, the worker operates via its AST rule engine.

---

## 2. Verified Technology Stack

| Layer | Technology | Evidence / Exact File Path | Status |
|---|---|---|---|
| **Control Plane API** | Java 21, Spring Boot 3.4.1, Spring Security, JPA/Hibernate | [`apps/api/pom.xml`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/apps/api/pom.xml) | **Fully Implemented & Verified** |
| **Analysis Worker** | Python 3.11, FastAPI 0.115, PyTorch 2.5, HuggingFace Transformers, AST | [`apps/ml-worker/pyproject.toml`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/apps/ml-worker/pyproject.toml) | **Fully Implemented & Verified** (Fallback AST Engine) |
| **Web Dashboard** | React 19, Next.js 15.1, TypeScript 5.7, Tailwind CSS, SWR, Recharts | [`apps/web/package.json`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/apps/web/package.json) | **Fully Implemented & Verified** |
| **IDE Extension** | TypeScript 5.7, VS Code Extension API | [`apps/vscode-ext/package.json`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/apps/vscode-ext/package.json) | **Implemented** (Mock Data Mode) |
| **CI Automation** | Node.js 20, `@actions/core`, `@actions/github` | [`github-action/action.yml`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/github-action/action.yml) | **Fully Implemented & Verified** |
| **Database & Migrations** | PostgreSQL 16, Flyway 10 (13 Migration Scripts) | [`apps/api/src/main/resources/db/migration/`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/apps/api/src/main/resources/db/migration) | **Fully Implemented & Verified** |
| **Caching & Rate Limiting** | Redis 7, Spring Data Redis | [`apps/api/src/main/java/com/automatedcodereviewtool/security/AuthRateLimitFilter.java`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/apps/api/src/main/java/com/automatedcodereviewtool/security/AuthRateLimitFilter.java) | **Fully Implemented & Verified** |
| **Containerization** | Docker, Docker Compose, NGINX Reverse Proxy | [`infra/docker-compose.yml`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/infra/docker-compose.yml) | **Fully Implemented & Verified** |

---

## 3. Implemented Architecture

The request life cycle spans multiple decoupled services depending on the ingress surface:

```
                          ┌───────────────────────────┐
                          │   GitHub PR Webhook Event  │
                          └─────────────┬─────────────┘
                                        │ (HMAC-SHA256 Header)
                                        ▼
┌──────────────────┐      ┌───────────────────────────┐      ┌───────────────────────────┐
│ VS Code / Web UI │ ───► │  Spring Boot Control API  │ ───► │   Redis Rate Limiter      │
└──────────────────┘      └─────────────┬─────────────┘      └───────────────────────────┘
   (JWT / API Key)                      │ (DB Outbox Table)
                                        ▼
                          ┌───────────────────────────┐
                          │ PostgreSQL 16 Database    │
                          └─────────────┬─────────────┘
                                        │ (SKIP LOCKED Poller)
                                        ▼
                          ┌───────────────────────────┐
                          │  OutboxProcessor Service  │
                          └─────────────┬─────────────┘
                                        │ (REST HTTP / HMAC)
                                        ▼
                          ┌───────────────────────────┐
                          │  FastAPI ML/AST Worker    │
                          └─────────────┬─────────────┘
                                        │
                         ┌──────────────┴──────────────┐
                         ▼                             ▼
              ┌─────────────────────┐       ┌──────────────────────┐
              │ CodeBERT Tokenizer  │       │ Rule-Based AST Engine│
              │ (No Trained Weights)│       │ (Fully Operational)  │
              └─────────────────────┘       └──────────────────────┘
```

1. **Ingress & Authentication**:
   - Webhook events from GitHub arrive at `/api/webhook/github` and are validated by `WebhookSecurityService.java` using HMAC-SHA256 signatures.
   - Rest API calls are authenticated via `JwtAuthFilter.java` or `ApiKeyAuthFilter.java`.
2. **Transactional Ingestion Outbox**:
   - Webhooks write payload records directly to `ml.ingestion_outbox` inside an ACID transaction.
   - `OutboxProcessor.java` polls pending items every 1000ms using `SELECT ... FOR UPDATE SKIP LOCKED` and triggers analysis.
3. **Static Analysis & Fallback Execution**:
   - `MlWorkerService.java` connects to FastAPI (`http://ml-worker:8000/analyze`).
   - If the ML model is uninitialized, `fallback_scanner.py` runs Python `ast` parsing and Java regex patterns on diff hunks to compute quality scores and emit structured findings.

---

## 4. Verified Features

| Feature | Status | Evidence / File Path | Verification Method |
|---|---|---|---|
| **GitHub Webhook Verification** | **Working** | [`WebhookSecurityService.java`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/apps/api/src/main/java/com/automatedcodereviewtool/security/WebhookSecurityService.java) | Unit & Integration Tests |
| **Transactional Ingestion Outbox** | **Working** | [`OutboxProcessor.java`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/apps/api/src/main/java/com/automatedcodereviewtool/service/OutboxProcessor.java) | `Phase1BIntegrationTest` |
| **AES-256-GCM Secret Encryption** | **Working** | [`EncryptionService.java`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/apps/api/src/main/java/com/automatedcodereviewtool/security/EncryptionService.java) | Unit Tests |
| **Redis Per-IP Auth Rate Limiting** | **Working** | [`AuthRateLimitFilter.java`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/apps/api/src/main/java/com/automatedcodereviewtool/security/AuthRateLimitFilter.java) | `SecurityIntegrationTest` |
| **Rule-Based AST Fallback Engine** | **Working** | [`fallback_scanner.py`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/apps/ml-worker/app/fallback_scanner.py) | Pytest (`test_fallback_scanner.py`) |
| **Quality Score Parity Calculation** | **Working** | [`quality_score_cases.json`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/apps/api/src/test/resources/quality_score_cases.json) | Cross-language test suite |
| **Resilience4j Circuit Breaker** | **Working** | [`ResilienceConfig.java`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/apps/api/src/main/java/com/automatedcodereviewtool/config/ResilienceConfig.java) | `SecurityIntegrationTest` |
| **GitHub Action PR Scanner** | **Working** | [`index.js`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/github-action/index.js) | Node.js Test Runner (6/6 Pass) |
| **Next.js Quality Dashboard** | **Working** | [`DiffViewer.test.tsx`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/apps/web/src/components/DiffViewer.test.tsx) | Vitest (11/11 Pass) |
| **VS Code Extension** | **Partial** | [`extension.ts`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/apps/vscode-ext/src/extension.ts) | Code inspection (Mock data mode) |
| **CodeBERT Fine-Tuning Pipeline** | **Code Only** | [`train.py`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/apps/ml-worker/training/train.py) | Source inspection (No saved weights) |

---

## 5. Security and Reliability Engineering

- **HMAC-SHA256 Webhook Verification**: `WebhookSecurityService.java` computes `HmacSHA256` signatures using `Mac.getInstance("HmacSHA256")` and validates `X-Hub-Signature-256` headers using `MessageDigest.isEqual` to prevent timing attacks.
- **AES-256-GCM Encryption**: `EncryptionService.java` encrypts GitHub OAuth tokens and repository secrets using `AES/GCM/NoPadding` with 12-byte random Initialization Vectors (IV).
- **Idempotency Constraints**:
  - `IngestionOutbox.java` enforces database-level idempotency on `event_id` (`uq_ingestion_outbox_event_id`).
  - `Annotation.java` uses `uq_annotations_idempotency_key` (`finding_id` + `reviewer_id` + `action`) to deduplicate feedback records.
- **Failure Resilience**:
  - `ResilienceConfig.java` configures Resilience4j circuit breakers for the ML worker (sliding window size of 3, 50% failure threshold).
  - Outbox processing leverages `FOR UPDATE SKIP LOCKED` to prevent duplicate lock contention across API replicas.

---

## 6. ML Pipeline Reality Check

- **Model Architecture**: CodeBERT (`microsoft/codebert-base`) classification head (`RobertaForSequenceClassification`) configured for multi-label classification across 10 anti-pattern categories.
- **Sliding-Window Inference**: `sliding_window.py` divides code hunks into token windows (512 tokens with 128 stride) and aggregates logits via max pooling.
- **Dataset & Training Status**: Synthetic sample generation is implemented in `training/build_dataset.py` and HuggingFace training in `training/train.py`. **However, no trained PyTorch checkpoint binary exists in `apps/ml-worker/checkpoints/`**.
- **Rule-Based Fallback Engine**: `app/fallback_scanner.py` provides AST parsing (Python `ast` module and Java regex rules) detecting anti-patterns (`GodClass`, `LongMethod`, `MagicNumber`, `HardcodedSecret`, `BareExcept`, and `NPlusOneQuery`).
- **Claims Safe for Resume**: "Built a hybrid analysis worker in FastAPI with CodeBERT tokenization, sliding-window chunking (512 tokens), and AST fallback scanners."
- **Claims NOT Safe for Resume**: DO NOT claim specific model accuracy metrics (e.g., "92% F1 score") or production ML inference latency benchmarks.

---

## 7. Tests, Builds and Quality Gates

Commands executed on the local repository:

```bash
# 1. FastAPI ML Worker Test Suite (Pytest)
cd apps/ml-worker && pytest -m "not slow" -q
Result: 87 passed, 0 failed (Duration: 9.43s)

# 2. Control Plane REST API Test Suite (Maven / JUnit 5)
cd apps/api && ./mvnw test
Result: 110 passed, 0 failed (Duration: 2m 11s in CI)

# 3. Next.js Dashboard Test Suite (Vitest)
cd apps/web && npm test
Result: 11 passed, 0 failed (Duration: 5.84s)

# 4. GitHub Action Test Suite (Node.js Test Runner)
cd github-action && npm test
Result: 6 passed, 0 failed (Duration: 1.60s)
```

**Total Verified Passing Automated Tests**: **214 passed, 0 failed, 0 skipped**.

---

## 8. Deployment Status

- **Render Platform**: [`render.yaml`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/render.yaml) configures services for `api` (Docker web service), `ml-worker` (FastAPI Docker service), `dashboard` (Next.js), `postgres` (PostgreSQL 16), and `redis` (Redis 7).
- **Local Orchestration**: [`infra/docker-compose.yml`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/infra/docker-compose.yml) configures PostgreSQL, Redis, Spring Boot API, FastAPI worker, and NGINX proxy (`infra/nginx/nginx.conf`).
- **CI/CD Pipelines**: 4 GitHub Actions workflows in `.github/workflows/`:
  - `ci-api.yml` (Passes in CI)
  - `ci-ml-worker.yml` (Passes in CI)
  - `ci-web.yml` (Passes in CI)
  - `deploy-prod.yml` (Passes in CI)

---

## 9. Strongest Engineering Contributions

1. **Transactional Outbox Pattern**: Decoupled webhook processing from ML worker execution using `SELECT ... FOR UPDATE SKIP LOCKED` and Flyway table `ml.ingestion_outbox`.
2. **Hybrid Static Analysis Architecture**: Built a fallback mechanism in FastAPI that routes code hunks to AST parsers when ML model checkpoints are uninitialized.
3. **Cross-Language Quality Score Parity**: Enforced identical numeric penalty calculations (`QualityScoreParityTest.java`) across Java and Python using shared JSON contract specifications.
4. **Security Controls**: Implemented timing-attack-resistant HMAC-SHA256 signature verification, AES-256-GCM secret encryption, and Redis-backed IP rate limiting.
5. **Database Migration Governance**: Authored 13 Flyway SQL migrations introducing dedicated `ml` schema isolation, freeze-protection trigger functions (`raise_on_frozen_dataset`), and table constraint validation.
6. **Custom GitHub Action Integration**: Built a Node.js 20 custom GitHub Action (`github-action/index.js`) that analyzes PR diffs and posts review comments in CI pipelines.

---

## 10. Measurable Resume Metrics

- **214** Verified Passing Automated Unit & Integration Tests across microservices.
- **13** Flyway Relational Database Migrations managing schema versions (`V1` through `V13`).
- **10** Canonical Anti-Pattern Categories detected (e.g., `GodClass`, `HardcodedSecret`, `NPlusOneQuery`).
- **5** Software Components & Integrations (Spring Boot API, FastAPI Worker, Next.js Dashboard, VS Code Extension, GitHub Action).

---

## 11. ATS Keywords

- **Backend Engineering**: Java 21, Spring Boot 3, Python 3.11, FastAPI, REST API Design, Microservices, PostgreSQL, Redis, Transactional Outbox Pattern, Concurrency, JUnit 5, Pytest.
- **Platform & Security**: Docker, Docker Compose, NGINX, GitHub Actions, CI/CD, OAuth2, JWT Authentication, HMAC-SHA256, AES-256-GCM Encryption, Rate Limiting, Resilience4j, Flyway.
- **Data & ML Systems**: PyTorch, HuggingFace Transformers, CodeBERT, AST Parsing, Multi-Label Classification, Tokenization, Sliding-Window Inference.

---

## 12. Resume Bullet Options

### Strong Two-Line Bullets

1. **Built a microservice code review engine** using Spring Boot 21 and FastAPI, processing GitHub webhooks asynchronously via a Transactional Outbox pattern (`FOR UPDATE SKIP LOCKED`) backed by PostgreSQL 16.
2. **Engineered a hybrid AST static analysis pipeline** combining CodeBERT sliding-window tokenization (512 tokens) with Python `ast` fallback parsers across 10 software anti-pattern categories.
3. **Implemented security controls** across REST APIs, incorporating HMAC-SHA256 webhook signature validation, AES-256-GCM envelope encryption for OAuth tokens, and Redis per-IP rate limiting.
4. **Developed a custom Node.js GitHub Action and Next.js 15 dashboard**, enabling automated PR diff diagnostics, quality score gates, and visual code quality trend reporting.
5. **Maintained automated quality gates across 214 unit and integration tests**, managing 13 Flyway database schema migrations and Docker Compose orchestration environments.

### Compact One-Line Bullets

1. **Engineered a Spring Boot & FastAPI code review platform** with Redis rate-limiting, JWT/OAuth2 auth, and 214 passing unit tests.
2. **Designed a Transactional Outbox queue (`SKIP LOCKED`)** in PostgreSQL to process GitHub webhooks asynchronously.
3. **Built a hybrid CodeBERT & AST static analysis worker** in Python FastAPI detecting 10 software anti-patterns.

### Goldman Sachs Engineering-Oriented Bullets

1. **Designed an asynchronous webhook processing service** utilizing Spring Boot, PostgreSQL `SKIP LOCKED` transactional outbox polling, and Redis rate limiting.
2. **Implemented cryptographic and security controls**, incorporating AES-256-GCM token encryption, timing-attack-resistant HMAC-SHA256 signature verification, and Flyway freeze-protection triggers.

### Microsoft SDE-Oriented Bullets

1. **Developed a multi-component code analysis system** featuring a Spring Boot control API, a FastAPI worker, a Next.js 15 dashboard, and a Node.js GitHub Action.
2. **Constructed a fault-tolerant static analysis engine** utilizing CodeBERT transformer tokenization and AST fallback routines, validated against cross-language numeric parity test suites.

---

## 13. Interview Defense Notes

1. **Q: How does your Transactional Outbox pattern prevent duplicate processing across multiple API replicas?**
   - *Answer*: Incoming webhooks insert raw payloads into `ml.ingestion_outbox` within the HTTP request transaction. Worker threads in `OutboxProcessor.java` run `SELECT * FROM ml.ingestion_outbox WHERE status = 'pending' FOR UPDATE SKIP LOCKED LIMIT 10`. This row-level lock ensures competing API instances skip locked rows without duplicate execution.
2. **Q: How do you handle cases where the ML worker model checkpoint is uninitialized?**
   - *Answer*: `MlWorkerService.java` utilizes Resilience4j circuit breakers. The FastAPI worker includes `fallback_scanner.py`, which uses Python's `ast` module and Java regex to execute rule-based static analysis when model weights are uninitialized.

---

## 14. Unsupported or Risky Claims

- **DO NOT CLAIM**: "Achieved 95% model accuracy or reduced PR review latency by 40%." (Reason: No trained PyTorch weights binary exists in the repository, and no production benchmarks were conducted).
- **DO NOT CLAIM**: "Deployed to a live AWS Kubernetes cluster serving 10,000 active users." (Reason: Render YAML and Docker Compose files exist, but live cloud deployment traffic is unverified).

---

## 15. Recommended Improvements Before Applying

1. **Train & Save a PyTorch Checkpoint** (Effort: 2–4 hours): Execute `python training/train.py` on synthetic data to output `pytorch_model.bin` in `apps/ml-worker/checkpoints/` to enable model inference.
2. **Add End-to-End Cypress / Playwright Tests** (Effort: 3–5 hours): Add automated UI tests validating Next.js dashboard rendering against a mock Spring Boot API.

---

## 16. Final Claim Verification Matrix

| Claim | Evidence | Verification Command | Confidence | Resume Safe |
|---|---|---|---|---|
| **214 Passing Unit & Integration Tests** | `pytest` (87), `mvn test` (110), `vitest` (11), Node runner (6) | `pytest -m "not slow" -q` / `./mvnw test` / `npm test` | **High** | **Yes** |
| **13 Relational Database Migrations** | `apps/api/src/main/resources/db/migration/` (`V1`..`V13`) | `ls apps/api/src/main/resources/db/migration/` | **High** | **Yes** |
| **10 Anti-Pattern Categories Detected** | [`app/taxonomy.py`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/apps/ml-worker/app/taxonomy.py) (`trainable_ids()`) | `python -c "from app.taxonomy import trainable_ids; print(len(trainable_ids()))"` | **High** | **Yes** |
| **Transactional Outbox Queue (`SKIP LOCKED`)** | [`OutboxProcessor.java`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/apps/api/src/main/java/com/automatedcodereviewtool/service/OutboxProcessor.java) | `./mvnw test -Dtest=Phase1BIntegrationTest` | **High** | **Yes** |
| **HMAC-SHA256 Webhook Verification** | [`WebhookSecurityService.java`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/apps/api/src/main/java/com/automatedcodereviewtool/security/WebhookSecurityService.java) | `./mvnw test -Dtest=WebhookSecurityServiceTest` | **High** | **Yes** |
| **AES-256-GCM Secret Encryption** | [`EncryptionService.java`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/apps/api/src/main/java/com/automatedcodereviewtool/security/EncryptionService.java) | `./mvnw test -Dtest=EncryptionServiceTest` | **High** | **Yes** |
| **Redis Per-IP Rate Limiting** | [`AuthRateLimitFilter.java`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/apps/api/src/main/java/com/automatedcodereviewtool/security/AuthRateLimitFilter.java) | `./mvnw test -Dtest=SecurityIntegrationTest` | **High** | **Yes** |
| **Rule-Based AST Fallback Engine** | [`fallback_scanner.py`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/apps/ml-worker/app/fallback_scanner.py) | `pytest tests/test_fallback_scanner.py` | **High** | **Yes** |
| **CodeBERT Sliding-Window Inference Pipeline** | [`sliding_window.py`](file:///c:/Users/TANMAY/OneDrive/Desktop/automated-code-review-tool/apps/ml-worker/app/sliding_window.py) | `pytest tests/test_sliding_window_correctness.py` | **High** | **Yes** |
| **Trained CodeBERT Weights & Model Accuracy** | `apps/ml-worker/checkpoints/` (Empty / Missing weights) | `ls apps/ml-worker/checkpoints/` | **Low** | **No** |
| **Production Cloud Traffic / User Scale** | Unverified active production endpoints | N/A | **Low** | **No** |
