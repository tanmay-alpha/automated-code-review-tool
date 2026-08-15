-- Make outbox claiming lease-based and remove legacy raw-diff payloads.

ALTER TABLE ml.ingestion_outbox
    ADD COLUMN IF NOT EXISTS max_attempts INT NOT NULL DEFAULT 5,
    ADD COLUMN IF NOT EXISTS locked_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS locked_by VARCHAR(100),
    ADD COLUMN IF NOT EXISTS dead_lettered_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS deduplication_key VARCHAR(255);

ALTER TABLE ml.ingestion_outbox
    ADD CONSTRAINT chk_outbox_attempt_bounds
        CHECK (attempt_count >= 0 AND max_attempts > 0 AND attempt_count <= max_attempts);

-- V10 payloads embedded the unredacted PR diff. Scrub it from every retained
-- row. Active legacy work cannot be replayed safely after removal, so make the
-- data loss explicit instead of silently completing it.
UPDATE ml.ingestion_outbox
   SET payload = payload - 'diff',
       status = CASE
           WHEN status IN ('pending', 'processing', 'failed') THEN 'dead_letter'
           ELSE status
       END,
       dead_lettered_at = CASE
           WHEN status IN ('pending', 'processing', 'failed') THEN NOW()
           ELSE dead_lettered_at
       END,
       last_error = CASE
           WHEN status IN ('pending', 'processing', 'failed')
               THEN 'Legacy raw diff scrubbed by V15; event requires explicit replay'
           ELSE last_error
       END,
       locked_at = NULL,
       locked_by = NULL,
       updated_at = NOW()
 WHERE payload ? 'diff';

CREATE UNIQUE INDEX IF NOT EXISTS uq_outbox_deduplication_key
    ON ml.ingestion_outbox(deduplication_key)
    WHERE deduplication_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_outbox_pending_eligible
    ON ml.ingestion_outbox(available_at, created_at)
    WHERE status = 'pending';

CREATE INDEX IF NOT EXISTS idx_outbox_processing_lease
    ON ml.ingestion_outbox(locked_at)
    WHERE status = 'processing';
