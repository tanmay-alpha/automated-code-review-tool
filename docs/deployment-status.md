# Deployment status

Checked 2026-11-10 against the release branch.

## Current topology

No hosted environment exists for this repository.

| Target | Evidence | Result |
| --- | --- | --- |
| Render | `https://automated-code-review-tool-{api,web,ml-worker}.onrender.com` return 404 with `x-render-routing: no-server` | Blueprint (`render.yaml`) never applied |
| Vercel | `vercel project ls` for the connected team lists no project for this repository | Not linked |
| GitHub | No deployments, no repository webhooks, no environments | Nothing deployed |

No paid resources were provisioned. `render.yaml` uses paid plans
(`basic-256mb` PostgreSQL, `starter` Key Value, `standard` web services), so
applying it is an owner decision.

## What has been verified instead

- Local production-like run: API jar + ML worker + PostgreSQL 16 + Redis,
  reached by real GitHub webhooks through a Cloudflare quick tunnel
  (`scripts/e2e/run_github_e2e.py`). Flyway applied V1–V24 on PostgreSQL; the
  outbox, leases and sticky publication ran without errors.
- CI builds every Docker image and scans it with Trivy.

## Requirements to deploy (Render blueprint)

1. Apply `render.yaml` from the Render dashboard (Blueprints → New).
2. Set the `sync: false` values:
   - API `SPRING_DATASOURCE_URL`: the database's internal JDBC URL
     (`jdbc:postgresql://<internal host>:5432/automated_code_review_tool`).
   - API `FRONTEND_URL` and `APP_BASE_URL`: the web service's public origin.
   - API `WEBHOOK_CALLBACK_URL`: `<API public origin>/api/webhook/github`.
   - API `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET`: from a GitHub OAuth App
     whose callback URL is `<web origin>/api/auth/callback`.
3. Back up the generated `ENCRYPTION_KEY` outside Render.
4. Run `docs/release-checklist.md` sections 3 and 4 against the deployed SHA.
5. Validate SEC-12 (`True-Client-IP` handling at Render's edge) before relying
   on per-IP auth rate limits.

Preview and production must use separate OAuth Apps, databases and generated
secrets; the blueprint generates `JWT_SECRET`, `ENCRYPTION_KEY` and
`ML_WORKER_SECRET` per service instance.
