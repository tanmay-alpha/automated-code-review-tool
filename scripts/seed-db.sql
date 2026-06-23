-- CodeLens local-dev seed SQL. Runs automatically the first time
-- `docker compose up` is invoked (against an empty postgres volume).
--
-- This file is intentionally minimal — schema is owned by Spring Boot /
-- Flyway migrations once the api container comes up. The only job of
-- this script is to grant the application role any extras it can't get
-- from the env, and to set sane defaults that make local poking easier.

-- (Optional) enable pg_stat_statements for query-perf debugging.
-- CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- Done — schema migrations are applied by Spring Boot on api startup.
SELECT 'CodeLens dev DB ready — schema will be created by Spring Boot Flyway migrations.' AS status;