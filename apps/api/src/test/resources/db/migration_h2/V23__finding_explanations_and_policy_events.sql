-- H2 mirror of V23__finding_explanations_and_policy_events.sql.
ALTER TABLE findings ADD COLUMN IF NOT EXISTS evidence VARCHAR(200);
ALTER TABLE findings ADD COLUMN IF NOT EXISTS remediation VARCHAR(300);
ALTER TABLE findings ADD COLUMN IF NOT EXISTS caveat VARCHAR(300);
-- The H2 mirror of V10 has no prediction status CHECK constraint, so the
-- 'filtered_by_repo_settings' status needs no change here.
