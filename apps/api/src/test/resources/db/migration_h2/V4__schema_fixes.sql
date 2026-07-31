-- ============================================
-- V4 (H2 mirror): schema fixes for tests
-- ============================================
-- H2-compatible equivalent of the Postgres V4 migration.
-- Adds status/disposition columns used by Finding entity
-- and cleans up redundant api_keys constraints.

ALTER TABLE findings ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'open' NOT NULL;
ALTER TABLE findings ADD COLUMN IF NOT EXISTS disposition_at TIMESTAMP;

CREATE TABLE IF NOT EXISTS anti_patterns (
    id               VARCHAR(80)  PRIMARY KEY,
    display_name     VARCHAR(120) NOT NULL,
    category         VARCHAR(30)  NOT NULL,
    default_severity VARCHAR(10)  NOT NULL DEFAULT 'minor',
    description      TEXT,
    trainable        BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
