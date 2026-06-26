# infra/

Production and local-dev infrastructure files for CodeLens.

| Path                          | Purpose                                                                                       |
| ----------------------------- | --------------------------------------------------------------------------------------------- |
| `docker-compose.yml`          | Local-dev full stack: postgres + redis + ml-worker + api + web.                               |
| `nginx/`                      | Reserved for production reverse-proxy configs (cert, gzip, rate-limit) — empty for now.       |

Copy `.env.example` to the repository root as `.env`, then run from this directory:

```powershell
docker compose --env-file ../.env up --build
```

The explicit `--env-file` is required because Compose otherwise searches this
directory rather than the repository root.
Every variable is documented in [ENGINEERING_PLAN.md §10](../ENGINEERING_PLAN.md#10-local-development-setup).
