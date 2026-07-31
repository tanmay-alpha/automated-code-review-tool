-- ============================================
-- V11 (H2 mirror): data-use fields + freeze protection
-- ============================================
-- Simplified for H2 (no JSONB, no triggers, no REFERENCES to public schema).

ALTER TABLE repositories ADD COLUMN IF NOT EXISTS license_spdx VARCHAR(30);
ALTER TABLE repositories ADD COLUMN IF NOT EXISTS data_use_status VARCHAR(40) NOT NULL DEFAULT 'quarantined_unknown_license';
ALTER TABLE repositories ADD COLUMN IF NOT EXISTS consent_recorded_at TIMESTAMP;

ALTER TABLE ml.code_samples ADD COLUMN IF NOT EXISTS repository_visibility VARCHAR(20) NOT NULL DEFAULT 'private';
ALTER TABLE ml.code_samples ADD COLUMN IF NOT EXISTS license_spdx VARCHAR(30);
ALTER TABLE ml.code_samples ADD COLUMN IF NOT EXISTS data_use_status VARCHAR(40) NOT NULL DEFAULT 'quarantined_unknown_license';
ALTER TABLE ml.code_samples ADD COLUMN IF NOT EXISTS source_url TEXT;

CREATE INDEX IF NOT EXISTS idx_repos_data_use ON repositories(data_use_status);
CREATE INDEX IF NOT EXISTS idx_code_samples_data_use ON ml.code_samples(data_use_status);

-- H2 does not support triggers or complex CHECK constraints for data-use.
-- Application-level enforcement is tested in Java integration tests.
