ALTER TABLE ml.code_samples
    ADD COLUMN IF NOT EXISTS hunk_sha256 VARCHAR(64) NOT NULL DEFAULT REPEAT('0', 64);

CREATE INDEX IF NOT EXISTS idx_code_samples_hunk_sha256
    ON ml.code_samples(hunk_sha256);
