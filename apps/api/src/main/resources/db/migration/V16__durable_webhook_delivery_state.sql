-- Turn delivery deduplication into a durable, retryable ingress queue.

ALTER TABLE processed_webhooks
    ADD COLUMN IF NOT EXISTS github_pr_number INT,
    ADD COLUMN IF NOT EXISTS head_sha VARCHAR(40),
    ADD COLUMN IF NOT EXISTS action VARCHAR(30),
    ADD COLUMN IF NOT EXISTS status VARCHAR(20),
    ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS max_attempts INT NOT NULL DEFAULT 5,
    ADD COLUMN IF NOT EXISTS available_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS processing_started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_error TEXT,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- Rows created by the old "processed means received" implementation cannot
-- be replayed because their event coordinates were not retained.
UPDATE processed_webhooks
   SET status = 'completed',
       completed_at = COALESCE(completed_at, processed_at),
       updated_at = NOW()
 WHERE status IS NULL;

ALTER TABLE processed_webhooks
    ALTER COLUMN status SET DEFAULT 'received',
    ALTER COLUMN status SET NOT NULL;

ALTER TABLE processed_webhooks
    ADD CONSTRAINT chk_processed_webhook_status
        CHECK (status IN ('received', 'processing', 'completed', 'failed', 'dead_letter', 'superseded')),
    ADD CONSTRAINT chk_processed_webhook_attempts
        CHECK (attempt_count >= 0 AND max_attempts > 0 AND attempt_count <= max_attempts);

CREATE UNIQUE INDEX IF NOT EXISTS uq_processed_webhook_logical_review
    ON processed_webhooks(repo_id, github_pr_number, head_sha)
    WHERE repo_id IS NOT NULL AND github_pr_number IS NOT NULL AND head_sha IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_processed_webhook_eligible
    ON processed_webhooks(status, available_at);

CREATE INDEX IF NOT EXISTS idx_processed_webhook_processing_lease
    ON processed_webhooks(processing_started_at)
    WHERE status = 'processing';
