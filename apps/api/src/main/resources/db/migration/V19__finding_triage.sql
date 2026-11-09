-- ============================================
-- V19: documented finding triage
-- ============================================
-- POST /api/scan/action now records who triaged a finding, when, and why.
--   * status gains 'false_positive' (the detector was wrong, as opposed to
--     'dismissed': correct but not worth acting on).
--   * triage_reason is required by the API for dismiss and false_positive.
--     The database enforces it for false_positive only: findings dismissed
--     before V19 have no recorded reason and must remain valid rows.
--   * triaged_at replaces disposition_at (TIMESTAMP without time zone);
--     triaged_by references the user who made the latest decision.

ALTER TABLE findings
    ADD COLUMN IF NOT EXISTS triage_reason VARCHAR(500),
    ADD COLUMN IF NOT EXISTS triaged_at    TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS triaged_by    UUID REFERENCES users(id) ON DELETE SET NULL;

-- disposition_at was written in the session time zone, so the implicit
-- timestamp -> timestamptz cast reads it back with the same zone.
UPDATE findings
   SET triaged_at = disposition_at
 WHERE disposition_at IS NOT NULL;

ALTER TABLE findings DROP COLUMN IF EXISTS disposition_at;

ALTER TABLE findings DROP CONSTRAINT IF EXISTS chk_finding_status;
ALTER TABLE findings
    ADD CONSTRAINT chk_finding_status
        CHECK (status IN ('open', 'accepted', 'dismissed', 'fixed', 'false_positive'));

ALTER TABLE findings
    ADD CONSTRAINT chk_finding_false_positive_reason
        CHECK (status <> 'false_positive'
               OR (triage_reason IS NOT NULL AND length(btrim(triage_reason)) > 0));

COMMENT ON COLUMN findings.status IS 'open | accepted | dismissed | fixed | false_positive';
COMMENT ON COLUMN findings.triage_reason IS 'Trimmed reviewer reason (<= 500 chars); required by the API for dismissed and false_positive';
COMMENT ON COLUMN findings.triaged_at IS 'Time of the latest triage action (including reopen)';
COMMENT ON COLUMN findings.triaged_by IS 'User who performed the latest triage action';
