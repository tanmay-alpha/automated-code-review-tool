-- ============================================
-- V4 (H2 mirror): schema fixes for tests
-- ============================================
-- H2-compatible equivalent of the Postgres V4 migration.
-- Adds status/disposition columns used by Finding entity
-- and cleans up redundant api_keys constraints.

ALTER TABLE findings ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'open' NOT NULL;
ALTER TABLE findings ADD COLUMN IF NOT EXISTS disposition_at TIMESTAMP;
