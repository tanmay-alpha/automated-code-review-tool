-- ============================================
-- V8 (H2 mirror): record model provenance on findings
-- ============================================
-- Adds nullable columns to the findings table so each persisted
-- finding records the engine, model version, and taxonomy version
-- that produced it.

ALTER TABLE findings ADD COLUMN IF NOT EXISTS engine VARCHAR(60);

ALTER TABLE findings ADD COLUMN IF NOT EXISTS model_version VARCHAR(40);

ALTER TABLE findings ADD COLUMN IF NOT EXISTS taxonomy_version VARCHAR(40);

CREATE INDEX IF NOT EXISTS idx_findings_engine_model
    ON findings (engine, model_version);
