-- V19 (H2 mirror): documented finding triage. See db/migration/V19.
-- Earlier H2 mirrors never added chk_finding_status, so it is created here.
ALTER TABLE findings ADD COLUMN IF NOT EXISTS triage_reason VARCHAR(500);
ALTER TABLE findings ADD COLUMN IF NOT EXISTS triaged_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE findings ADD COLUMN IF NOT EXISTS triaged_by UUID;
ALTER TABLE findings ADD CONSTRAINT fk_findings_triaged_by
    FOREIGN KEY (triaged_by) REFERENCES users(id) ON DELETE SET NULL;

UPDATE findings SET triaged_at = disposition_at WHERE disposition_at IS NOT NULL;
ALTER TABLE findings DROP COLUMN IF EXISTS disposition_at;

ALTER TABLE findings DROP CONSTRAINT IF EXISTS chk_finding_status;
ALTER TABLE findings ADD CONSTRAINT chk_finding_status
    CHECK (status IN ('open', 'accepted', 'dismissed', 'fixed', 'false_positive'));
ALTER TABLE findings ADD CONSTRAINT chk_finding_false_positive_reason
    CHECK (status <> 'false_positive'
           OR (triage_reason IS NOT NULL AND LENGTH(TRIM(triage_reason)) > 0));
