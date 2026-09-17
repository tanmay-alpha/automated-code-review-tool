# Automated Code Review Tool — Java Spring Boot Backend with ML-assisted Code Analysis

The **Automated Code Review Tool** is an enterprise-grade, multi-service code review platform designed to analyze pull requests, detect software anti-patterns, calculate code quality metrics, and automate developer feedback loops.

The central core of the system is a robust **Java 21 + Spring Boot** application acting as the primary control plane and API gateway, orchestrating data storage, security, GitHub webhooks, and asynchronous communication with a specialized Python/FastAPI Machine Learning inference worker.

---

## Architectural Overview

The platform uses a decoupled microservice architecture where Java Spring Boot orchestrates all business logic and external integrations:

- **Java 21 & Spring Boot (Central Control Plane & Backend)**:
  - Exposes secured REST APIs for dashboards, extensions, and CI pipelines.
  - Implements authentication and security: JWT generation/validation, API key authentication, OAuth2 GitHub authorization flow, rate limiting, and RBAC.
  - Manages GitHub lifecycle integrations: webhook ingestion, HMAC signature verification, pull request diff parsing, status checks, and inline review comments.
  - Controls repository management, review scheduling, and data ingestion pipelines.
  - Manages relational persistence in **PostgreSQL** using Spring Data JPA with versioned schema migrations managed by **Flyway**.
  - Coordinates Redis-backed token blacklisting and outbox worker state.
  - Dispatches code hunks to the ML worker and aggregates review findings.
- **Next.js Dashboard (Frontend)**:
  - React, TypeScript, and TailwindCSS interface for repository monitoring, PR quality trends, anti-pattern breakdowns, and API key management.
- **Python ML Worker (Inference Engine & Model Pipeline)**:
  - Dedicated FastAPI service used strictly for AST diff parsing, secret redaction, rule-based fallback detection, and transformer-based code classification (CodeBERT).
- **Client Integrations**:
  - **GitHub Action**: Automates CI PR reviews with quality-score gating.
  - **VS Code Extension**: Real-time in-editor anti-pattern diagnostics via the Spring Boot API.

```text
GitHub Webhook / Action / VS Code / Next.js Dashboard
                         |
                         v
      +-------------------------------------+
      |   Spring Boot Backend (Java 21)     |
      |   - REST API & Security (JWT / Key) |
      |   - GitHub Webhook & Diff Ingestion |
      |   - Review & Outbox Orchestration   |
      +-------------------------------------+
             |                 |
             v                 v
   PostgreSQL 16 + Redis   FastAPI ML Worker (Python)
                               |
                      localized diff hunks
                               |
                    +----------+----------+
                    |                     |
                    v                     v
              CodeBERT Model       Rule-based Fallback
```

---

## Java Backend

The primary Java application is located in [`apps/api/`](apps/api/) and follows standard Maven and Spring Boot conventions:

```text
apps/api/
├── pom.xml                                   # Maven dependencies, plugins, test profiles
└── src/
    ├── main/
    │   ├── java/com/automatedcodereviewtool/ # Core Java source code
    │   └── resources/                        # application.yml, Flyway SQL migrations
    └── test/
        ├── java/com/automatedcodereviewtool/ # JUnit 5 unit, slice, and integration tests
        └── resources/                        # Test configurations & H2 migration scripts
```

### Core Java Packages

The Java backend adheres to clear separation of concerns across its package hierarchy:

| Package | Purpose & Responsibilities |
| --- | --- |
| `config` | Spring configuration beans: WebClient timeouts, Redis caching, CORS policies, async executor pools, and Resilience4j circuit breakers. |
| `controller` | REST controllers exposing versioned endpoints for scans (`/api/scan`), pull request reviews (`/api/reviews`), repositories (`/api/repos`), API keys (`/api/keys`), system metrics (`/api/metrics`), and webhooks (`/api/webhook`). |
| `dto` | Strongly-typed request/response data transfer objects, validated with Jakarta Bean Validation (`@NotNull`, `@NotBlank`, `@Size`). |
| `entity` | JPA domain models mapped to PostgreSQL tables: `RepositoryEntity`, `PullRequestEntity`, `Review`, `Finding`, `CodeSample`, `ApiKey`, `ProcessedWebhook`, and `IngestionOutbox`. |
| `exception` | Domain-specific exception hierarchy (`ResourceNotFoundException`, `UnauthorizedException`, `MlWorkerException`) handled globally by `@RestControllerAdvice`. |
| `repository` | Spring Data JPA repositories with custom transactional queries, row-level locking (`SELECT FOR UPDATE`), and pagination support. |
| `security` | Authentication filter chains, JWT validation (`JwtAuthFilter`), API key authentication (`ApiKeyAuthFilter`), IP rate limiting (`AuthRateLimitFilter`), and cryptographic utilities (`EncryptionService`). |
| `service` | Core business logic layer coordinating GitHub interactions (`GitHubService`), review workflows (`ReviewService`), ML worker HTTP client (`MlWorkerService`), and outbox publishing (`OutboxProcessor`). |
| `webhook` | GitHub webhook ingress processor: validates HMAC-SHA256 signatures, deduplicates deliveries, and triggers async review pipelines. |

### Architectural Data Flow

1. **Controller Layer → Service Layer → Repository Layer → PostgreSQL**:
   Requests entering the Spring Boot application are validated in the controllers, processed within transactional boundaries in the service layer, and persisted to PostgreSQL using Spring Data JPA and Flyway versioned schemas.
2. **GitHub → Spring Boot API → ML Worker → Review Result**:
   When a pull request webhook or scan request arrives, the Java API verifies signatures, fetches the raw unified diff, redacts secrets, delegates code hunk classification to the FastAPI ML worker, computes overall quality scores, and records findings in PostgreSQL while optionally commenting back onto the GitHub pull request.

---

## Production & Machine Learning Status

| Capability | Current State | Notes |
| --- | --- | --- |
| Primary Production Detector | Deterministic rule-based engine | Fast, deterministic AST/regex pattern detection. |
| CodeBERT Checkpoint | Supported, not bundled in Git | Model checkpoint weights are maintained in artifact storage. |
| Fallback Operation | Default (`MODEL_NAME=none`) | System operates fully on deterministic rules when no ML model is deployed. |
| Dataset Ingestion | Database outbox & contract validation | Hunks are normalized, redacted, and versioned before ingestion. |

The repository contains transformer training and inference code, but does not claim unverified performance benchmarks. A model must satisfy explicit dataset contracts, baseline comparisons, and deployment smoke tests before promotion.

---

## Repository Layout

| Path | Purpose |
| --- | --- |
| `apps/api/` | Java 21 + Spring Boot control plane and PostgreSQL migrations |
| `apps/ml-worker/` | Python FastAPI inference service and CodeBERT lifecycle pipeline |
| `apps/web/` | Next.js / TypeScript dashboard frontend |
| `apps/vscode-ext/` | VS Code extension client |
| `github-action/` | GitHub Action integration for pull-request CI |
| `contracts/` | Shared JSON contract fixtures for cross-service parity tests |
| `taxonomy/` | Concrete anti-pattern taxonomy specification (`anti_patterns.yaml`) |
| `infra/docker-compose.yml` | Full-stack local development environment |
| `render.yaml` | Production deployment blueprint |

---

## Local Full Stack Setup

### Prerequisites
- Docker Engine with Docker Compose v2
- Java 21 (JDK) and Maven 3.9+ (for local Java development)
- Git

### Running with Docker Compose

```bash
git clone https://github.com/tanmay-alpha/automated-code-review-tool.git
cd automated-code-review-tool
cp .env.example .env
docker compose --env-file .env -f infra/docker-compose.yml up --build
```

Local endpoints:
- Dashboard: `http://localhost:3000`
- Java API Health: `http://localhost:8080/actuator/health`
- ML Worker Health: `http://localhost:8000/ml/health`

To stop services:
```bash
docker compose --env-file .env -f infra/docker-compose.yml down
```

---

## Verification & Testing

Run all commands from the repository root unless a `cd` is noted:

```bash
# 1. Java API (Maven + JUnit 5)
cd apps/api
mvn -B -ntp test
# Optional: PostgreSQL Testcontainers correctness suite (requires Docker)
mvn -B -ntp -Ppostgres-correctness test

# 2. Python ML Worker
cd ../ml-worker
ruff check app training tests
mypy app training
pytest -m "not slow" -q

# 3. Next.js Web Frontend
cd ../web
npm ci
npx tsc --noEmit
npm test
npm run build

# 4. GitHub Action
cd ../../github-action
npm ci
npm test
npm run build
git diff --exit-code -- dist

# 5. VS Code Extension
cd ../apps/vscode-ext
npm ci
npm run compile
npm test

# 6. Container Builds (Context: Repository Root)
cd ../..
docker compose --env-file .env.example -f infra/docker-compose.yml config
docker build -f apps/api/Dockerfile -t automated-code-review-tool-api:local .
docker build -f apps/ml-worker/Dockerfile -t automated-code-review-tool-ml:local .
docker build -f apps/web/Dockerfile -t automated-code-review-tool-web:local apps/web
```

---

## Security & Data Integrity

- Never commit `.env`, private keys, secrets, or model checkpoint weights to source control.
- All code samples undergo secret redaction before ML processing or persistence.
- Sensitive credentials (GitHub tokens, encryption keys, webhook secrets) are strictly isolated via environment variables.

---

## License

[MIT](LICENSE) © 2026 Tanmay Mangal.
