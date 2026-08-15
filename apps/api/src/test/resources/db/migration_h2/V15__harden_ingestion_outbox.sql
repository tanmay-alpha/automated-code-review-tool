ALTER TABLE ml.ingestion_outbox ADD COLUMN IF NOT EXISTS max_attempts INT NOT NULL DEFAULT 5;
ALTER TABLE ml.ingestion_outbox ADD COLUMN IF NOT EXISTS locked_at TIMESTAMP;
ALTER TABLE ml.ingestion_outbox ADD COLUMN IF NOT EXISTS locked_by VARCHAR(100);
ALTER TABLE ml.ingestion_outbox ADD COLUMN IF NOT EXISTS dead_lettered_at TIMESTAMP;
ALTER TABLE ml.ingestion_outbox ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ml.ingestion_outbox ADD COLUMN IF NOT EXISTS deduplication_key VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS uq_outbox_deduplication_key
    ON ml.ingestion_outbox(deduplication_key);
