# Release checklist

Use this list for every production release. A release is the deployed commit
SHA of `main`; record it in the release notes together with the evidence for
each gate.

## 1. Source gates (GitHub)

- [ ] All five workflows are green on the release SHA: `ci-api`, `ci-ml-worker`,
      `ci-web`, `ci-integrations`, `ci-platform`.
- [ ] `ci-api` ran both the H2 suite and the PostgreSQL Testcontainers suite
      (`postgres-correctness`), including Flyway fresh-install and upgrade tests.
- [ ] Secret scan (gitleaks), dependency audits (npm audit, pip-audit) and the
      container/filesystem vulnerability scan have no unreviewed HIGH/CRITICAL
      findings. Accepted advisories are listed in `docs/security-review.md`.
- [ ] GitHub Action `dist/` matches a fresh `npm run build`.
- [ ] Review benchmark (`python benchmarks/run_benchmark.py`) ran in CI; held-out
      precision/recall did not regress versus `docs/review-benchmark.md` without
      an explained reason.

## 2. Configuration

- [ ] `JWT_SECRET`, `ENCRYPTION_KEY`, `ML_WORKER_SECRET` are generated per
      environment; preview/staging never reuse production values.
- [ ] `ENCRYPTION_KEY` is backed up outside the platform. Losing it makes every
      stored GitHub token and webhook secret undecryptable.
- [ ] `FRONTEND_URL` / `APP_BASE_URL` equal the public dashboard origin, and the
      GitHub OAuth app callback is `<dashboard origin>/api/auth/callback`.
- [ ] `WEBHOOK_CALLBACK_URL` is reachable from GitHub over HTTPS.
- [ ] `COOKIE_SECURE=true`. `AUTH_TRUSTED_PROXIES` / `AUTH_CLIENT_IP_HEADER`
      match the actual ingress.
- [ ] `MODEL_NAME=none` unless a checkpoint has passed the promotion procedure
      in `docs/ml-system-design.md`.

## 3. Deployment

- [ ] Flyway applied cleanly on startup (API log `Successfully applied` or
      `Schema … is up to date`); no `validate` failures.
- [ ] `GET /actuator/health` returns `UP` on the API; `GET /ml/health` reports the
      expected `engine`.
- [ ] Signed-in `GET /api/diagnostics` reports database, Redis, ML worker and
      GitHub as `up` and an empty dead-letter queue.
- [ ] Deployed SHA equals the release SHA (Render deploy page or image label).

## 4. Post-deployment integration test

Run against a disposable repository seeded with
`examples/demo-repo/seed_demo_repo.py`; never against a real user repository.

- [ ] Sign in through GitHub OAuth from the dashboard origin.
- [ ] Connect the sandbox repository; confirm GitHub shows the webhook with a
      successful `ping` delivery.
- [ ] Push a commit to a seeded PR; the PR gets exactly one summary comment
      for the new head SHA, with the expected findings.
- [ ] Redeliver the same webhook from GitHub's UI; no second comment appears.
- [ ] Push another commit; the same comment is updated to the new head SHA.
- [ ] Triage one finding as false positive with a reason; it persists across
      reloads.
- [ ] Disconnect the repository; GitHub no longer lists the webhook.
- [ ] Delete the sandbox repository.

## 5. Rollback

- [ ] Previous deploy is retained in Render and can be redeployed.
- [ ] Migrations in the release are additive (no destructive DDL) or the
      rollback plan for them is written in the PR description.
