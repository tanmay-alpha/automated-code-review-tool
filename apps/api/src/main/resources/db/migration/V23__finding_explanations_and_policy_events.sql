-- Explainability fields reported by the ML worker for every finding:
-- the triggering added line (secret-redacted before it is stored), how to
-- fix it, and when the finding may not apply. Bounded to the worker contract.
ALTER TABLE findings
    ADD COLUMN IF NOT EXISTS evidence    VARCHAR(200),
    ADD COLUMN IF NOT EXISTS remediation VARCHAR(300),
    ADD COLUMN IF NOT EXISTS caveat      VARCHAR(300);

-- Worker predictions excluded by a repository's review settings are still
-- recorded, so the prediction log stays a complete record of worker output.
ALTER TABLE ml.prediction_events DROP CONSTRAINT chk_prediction_status;
ALTER TABLE ml.prediction_events ADD CONSTRAINT chk_prediction_status
    CHECK (status IN (
        'persisted',
        'rejected_unmapped_file',
        'rejected_invalid_line',
        'rejected_unknown_taxonomy',
        'rejected_invalid_confidence',
        'rejected_duplicate',
        'filtered_by_repo_settings'
    ));
