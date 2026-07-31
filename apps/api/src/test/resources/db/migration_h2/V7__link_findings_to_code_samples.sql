-- ============================================
-- V7 (H2 mirror): link findings to code samples
-- ============================================
-- Adds an optional FK from findings to code_samples so
-- every finding produced during a PR scan can be traced back
-- to the exact redacted hunk that triggered it.

ALTER TABLE findings ADD COLUMN IF NOT EXISTS code_sample_id UUID;

ALTER TABLE findings ADD CONSTRAINT fk_findings_code_sample
    FOREIGN KEY (code_sample_id)
    REFERENCES ml.code_samples(id)
    ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_findings_code_sample_id
    ON findings(code_sample_id);
