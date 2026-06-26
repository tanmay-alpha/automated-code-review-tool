-- CodeLens — initial schema (V1, H2 test dialect)
--
-- Test-only mirror of V1__initial_schema.sql for the in-memory H2 database
-- (PostgreSQL-compatibility mode). Two PostgreSQL-specific constructs
-- are removed because H2 2.x does not support them in PG-compat mode:
--
--   1. The `CREATE EXTENSION "pgcrypto"` line is dropped — H2 has
--      no concept of extensions.
--
--   2. `gen_random_uuid()` DEFAULT clauses on UUID PK columns are
--      removed. H2's PG-compat parser for `CREATE TABLE` does not
--      allow function calls in DEFAULT expressions (it expects only
--      literals). UUID PK values are populated at the application
--      layer by Hibernate's @GeneratedValue(UUID) anyway, so the
--      DB-side DEFAULT is just a backstop for non-JPA inserts that
--      the test suite never performs.
--
-- Production runs use the regular V1 in db/migration (Postgres).
-- Tests use this file because Spring's H2 embedded DB does not
-- support `CREATE EXTENSION` and doesn't accept function calls in
-- DEFAULT clauses.

-- ============================================
-- USERS
-- ============================================
CREATE TABLE users (
    id               UUID        PRIMARY KEY,
    github_id        BIGINT      UNIQUE NOT NULL,
    github_username  VARCHAR(100) NOT NULL,
    avatar_url       TEXT,
    access_token     TEXT        NOT NULL,          -- GitHub OAuth token, AES-encrypted
    refresh_token    TEXT,                           -- JWT refresh token, bcrypt-hashed
    api_key_hash     VARCHAR(100),                   -- bcrypt hash of VS Code API key
    api_key_prefix   VARCHAR(10),                    -- "cl_live_xxx" prefix (for display)
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_users_github_id ON users(github_id);


-- ============================================
-- REPOSITORIES
-- ============================================
CREATE TABLE repositories (
    id               UUID        PRIMARY KEY,
    github_id        BIGINT      UNIQUE NOT NULL,
    full_name        VARCHAR(255) NOT NULL,           -- "tanmay-alpha/MAET"
    description      TEXT,
    is_private       BOOLEAN     DEFAULT FALSE,
    owner_id         UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    webhook_id       BIGINT,                          -- GitHub webhook ID
    webhook_secret   TEXT,                            -- HMAC secret (encrypted)
    is_active        BOOLEAN     DEFAULT TRUE,
    quality_score    DECIMAL(5,2),                    -- Latest rolling score
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_repos_owner_id ON repositories(owner_id);
CREATE INDEX idx_repos_github_id ON repositories(github_id);


-- ============================================
-- PULL REQUESTS
-- ============================================
CREATE TABLE pull_requests (
    id                  UUID        PRIMARY KEY,
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
    reviewed_at         TIMESTAMP WITH TIME ZONE,
    created_at          TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

    CONSTRAINT uq_pr_repo UNIQUE (repo_id, github_pr_number)
);

CREATE INDEX idx_prs_repo_id ON pull_requests(repo_id);
CREATE INDEX idx_prs_status  ON pull_requests(status);


-- ============================================
-- FINDINGS
-- ============================================
CREATE TABLE findings (
    id               UUID        PRIMARY KEY,
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
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_findings_pr_id   ON findings(pr_id);
CREATE INDEX idx_findings_category ON findings(category);


-- ============================================
-- QUALITY METRICS (pre-aggregated for charts)
-- ============================================
CREATE TABLE quality_metrics (
    id               UUID        PRIMARY KEY,
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
    processed_at     TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
