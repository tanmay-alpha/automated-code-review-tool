-- V2: add user-disposition tracking to findings (H2 test variant)
--
-- Mirror of V2__findings_disposition.sql. The Postgres multi-column
-- `ALTER TABLE ... ADD COLUMN IF NOT EXISTS (col1, col2, col3)`
-- syntax is not supported by H2 2.x in PostgreSQL-compat mode, so
-- each column is added in its own ALTER statement.
ALTER TABLE findings ADD COLUMN IF NOT EXISTS status          VARCHAR(20)  NOT NULL DEFAULT 'open';
ALTER TABLE findings ADD COLUMN IF NOT EXISTS disposition_at  TIMESTAMP;
ALTER TABLE findings ADD COLUMN IF NOT EXISTS updated_at      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP;

-- Backfill: mark all existing findings as open with no disposition
UPDATE findings SET status = 'open', updated_at = created_at
  WHERE updated_at IS NULL;
