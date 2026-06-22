-- V2: add user-disposition tracking to findings
ALTER TABLE findings
  ADD COLUMN IF NOT EXISTS status      VARCHAR(20)  NOT NULL DEFAULT 'open',
  ADD COLUMN IF NOT EXISTS disposition_at TIMESTAMP,
  ADD COLUMN IF NOT EXISTS updated_at  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP;

-- Backfill: mark all existing findings as open with no disposition
UPDATE findings SET status = 'open', updated_at = created_at
  WHERE updated_at IS NULL;