ALTER TABLE processed_webhooks ADD COLUMN IF NOT EXISTS repo_id UUID;
ALTER TABLE processed_webhooks ADD COLUMN IF NOT EXISTS github_pr_number INT;
ALTER TABLE processed_webhooks ADD COLUMN IF NOT EXISTS head_sha VARCHAR(40);
ALTER TABLE processed_webhooks ADD COLUMN IF NOT EXISTS action VARCHAR(30);
ALTER TABLE processed_webhooks ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'received';
ALTER TABLE processed_webhooks ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE processed_webhooks ADD COLUMN IF NOT EXISTS max_attempts INT NOT NULL DEFAULT 5;
ALTER TABLE processed_webhooks ADD COLUMN IF NOT EXISTS available_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE processed_webhooks ADD COLUMN IF NOT EXISTS processing_started_at TIMESTAMP;
ALTER TABLE processed_webhooks ADD COLUMN IF NOT EXISTS completed_at TIMESTAMP;
ALTER TABLE processed_webhooks ADD COLUMN IF NOT EXISTS last_error TEXT;
ALTER TABLE processed_webhooks ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS uq_processed_webhook_logical_review
    ON processed_webhooks(repo_id, github_pr_number, head_sha);
