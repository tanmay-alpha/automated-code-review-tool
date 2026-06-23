# infra/

Production and local-dev infrastructure files for CodeLens.

| Path                          | Purpose                                                                                       |
| ----------------------------- | --------------------------------------------------------------------------------------------- |
| `docker-compose.yml`          | Local-dev full stack: postgres + redis + ml-worker + api + web. Run `docker compose up`.      |
| `nginx/`                      | Reserved for production reverse-proxy configs (cert, gzip, rate-limit) — empty for now.       |

The compose file expects `.env` at the repo root (copy from `.env.example`).
Every variable is documented in [ENGINEERING_PLAN.md §10](../ENGINEERING_PLAN.md.md#10-local-development-setup).