# automated-code-review-tool

automated-code-review-tool is a multi-service code-review platform that
analyzes GitHub pull-request diffs using a deterministic fallback engine and an
optional versioned multi-label CodeBERT classifier. It includes redacted data
capture, human annotation, immutable dataset versioning, reproducible model
experiments, Spring Boot orchestration, a Next.js dashboard, a GitHub Action,
and a VS Code extension.

## Production today

| Capability | Current state |
| --- | --- |
| Production detector | Deterministic fallback |
| CodeBERT checkpoint | Not present or deployed |
| Frozen real dataset | Not present in Git |
| Verified model evaluation | Not available |
| Fallback operation | Supported with `MODEL_NAME=none` |

The repository contains transformer training and inference code, but it does
not claim model performance or a production CodeBERT deployment. A model must
pass the documented data, compatibility, evaluation, and smoke-test gates
before an operator explicitly promotes it.

## Architecture

```text
GitHub webhook / Action / VS Code / dashboard
                    |
                    v
           Spring Boot API (Java 21)
             |                 |
             v                 v
     PostgreSQL + Redis   FastAPI ML worker
                                |
                      localized diff hunks
                         |             |
                         v             v
                  approved model   rule fallback
```

The root [taxonomy YAML](taxonomy/anti_patterns.yaml) is the label-definition
source. The worker parses unified diffs into hunks and reports the detector,
taxonomy version, and localization it actually used. The API owns GitHub
integration, review state, feedback, dataset lineage, and durable processing.

See [ML system design](docs/ml-system-design.md) for the data and promotion
contracts and [the training guide](apps/ml-worker/training/README.md) for the
canonical lifecycle commands.

## Repository layout

| Path | Purpose |
| --- | --- |
| `apps/api/` | Spring Boot control plane and PostgreSQL migrations |
| `apps/ml-worker/` | FastAPI inference service and ML lifecycle tooling |
| `apps/web/` | Next.js dashboard |
| `apps/vscode-ext/` | Editor integration |
| `github-action/` | Pull-request workflow integration; committed `dist/` is required |
| `taxonomy/` | Canonical concrete anti-pattern taxonomy |
| `infra/docker-compose.yml` | Local full-stack environment |
| `render.yaml` | Production infrastructure declaration |

## Local full stack

Prerequisites are Docker with Compose v2 and Git.

```bash
git clone https://github.com/tanmay-alpha/automated-code-review-tool.git
cd automated-code-review-tool
cp .env.example .env
docker compose --env-file .env -f infra/docker-compose.yml up --build
```

The checked-in example uses public, local-only development values and starts
the ML worker in fallback mode. Replace the GitHub OAuth values before using
login or webhook flows, and replace every local secret before sharing a
deployment.

Local endpoints:

- dashboard: `http://localhost:3000`
- API health: `http://localhost:8080/actuator/health`
- ML health: `http://localhost:8000/ml/health`

Stop without deleting PostgreSQL data:

```bash
docker compose --env-file .env -f infra/docker-compose.yml down
```

## Production deployment

Render is the single production deployment target described by this
repository. [render.yaml](render.yaml) defines Render Postgres, Render Key
Value, the API, the fallback-mode ML worker, and the web dashboard. Render owns
deployment through Blueprint auto-deploys after checks pass; this repository
does not contain a second production deployment workflow.

Create a Render Blueprint from the repository, then provide the fields marked
`sync: false`:

1. Set `SPRING_DATASOURCE_URL` to the Render database's internal JDBC URL,
   using `jdbc:postgresql://host:port/database`. Username and password are wired
   from the managed database separately.
2. Set `APP_BASE_URL` to the public API origin and `FRONTEND_URL` to the public
   web origin.
3. Set `WEBHOOK_CALLBACK_URL` to
   `https://<api-host>/api/webhook/github`.
4. Set `GITHUB_CLIENT_ID` and `GITHUB_CLIENT_SECRET` from the production GitHub
   OAuth application.
5. Set the web service's `NEXT_PUBLIC_API_BASE_URL` to the public API origin.

Render generates the 256-bit JWT, encryption, and shared ML-worker secrets. The
Blueprint deliberately sets `MODEL_NAME=none`; adding a promoted private model
and its access token is a separate operator action.

## Developer integrations

- [GitHub Action](github-action/README.md): scans pull-request diffs and emits
  workflow annotations. Its bundled `dist/` must match `index.js`.
- [VS Code extension](apps/vscode-ext/README.md): scans supported files and
  renders deduplicated diagnostics with a timeout and 1 MB input guard.

Both clients require an API key. Send credentials only to HTTPS endpoints
outside localhost.

## Verification commands

Run commands from the repository root unless a `cd` is shown.

```bash
# Python worker
cd apps/ml-worker
ruff check app training tests
mypy app training
pytest -m "not slow" -q

# Java API
cd ../api
mvn -B -ntp test
mvn -B -ntp -Ppostgres-correctness test

# Web
cd ../web
npm ci
npx tsc --noEmit
npm test
npm run build

# GitHub Action
cd ../../github-action
npm ci
npm test
npm run build
git diff --exit-code -- dist

# VS Code extension
cd ../apps/vscode-ext
npm ci
npm run compile
npm test

# Container configuration
cd ../..
docker compose --env-file .env.example -f infra/docker-compose.yml config
docker build -f apps/ml-worker/Dockerfile -t automated-code-review-tool-ml:local .
docker build -f apps/api/Dockerfile -t automated-code-review-tool-api:local apps/api
docker build -f apps/web/Dockerfile -t automated-code-review-tool-web:local apps/web
```

The PostgreSQL profile is reserved for real Testcontainers correctness tests;
H2-only results are not evidence of PostgreSQL behavior.

## Security and data boundaries

- Never commit `.env`, raw datasets, model weights, checkpoints, or experiment
  output.
- Redact source before durable ML ingestion and retain the redaction version.
- Do not infer that a sample is clean merely because no finding exists.
- Keep test data out of threshold tuning, early stopping, and model selection.
- Treat GitHub tokens, API keys, webhook secrets, and annotation exports as
  sensitive data.

## License

[MIT](LICENSE) © 2026 Tanmay Mangal.
