# Local development env — copy from repo root .env.example and override these.
# Spring Boot runs on 8080 by default; Next.js dev server on 3000.
# Both services trust the browser's same-site cookies, so they must share an origin
# in production (single reverse-proxied host) — see ENGINEERING_PLAN.md.md §11.
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
