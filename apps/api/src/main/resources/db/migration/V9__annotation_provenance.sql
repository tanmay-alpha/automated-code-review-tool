-- ============================================
-- V9: reviewer-aware annotation provenance
-- ============================================
-- Replaces free-text-rationale idempotency with explicit
-- database-enforced columns. Every annotation now records:
--   * which finding it applies to
--   * which reviewer produced it
--   * what action was taken
--   * a deterministic idempotency key
--   * whether it supersedes a prior annotation
--   * its current resolution state
--
-- The UNIQUE(idempotency_key) constraint enforces that the same
-- reviewer submitting the same action for the same finding is
-- silently deduplicated at the database level.
-- ============================================

-- Add provenance columns.
ALTER TABLE ml.annotations
    ADD COLUMN IF NOT EXISTS finding_id UUID;

ALTER TABLE ml.annotations
    ADD COLUMN IF NOT EXISTS reviewer_user_id UUID;

ALTER TABLE ml.annotations
    ADD COLUMN IF NOT EXISTS feedback_action VARCHAR(20);

ALTER TABLE ml.annotations
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(100);

ALTER TABLE ml.annotations
    ADD COLUMN IF NOT EXISTS supersedes_annotation_id UUID;

ALTER TABLE ml.annotations
    ADD COLUMN IF NOT EXISTS resolution_state VARCHAR(20) NOT NULL DEFAULT 'active';

-- Allowed feedback actions.
ALTER TABLE ml.annotations
    ADD CONSTRAINT chk_annotation_feedback_action
        CHECK (feedback_action IS NULL OR feedback_action IN (
            'accepted', 'dismissed', 'fixed',
            'manual_positive', 'manual_negative', 'manual_uncertain'
        ));

-- Allowed resolution states.
ALTER TABLE ml.annotations
    ADD CONSTRAINT chk_annotation_resolution_state
        CHECK (resolution_state IN ('active', 'superseded', 'retracted'));

-- Foreign key: annotation can reference the finding it annotates.
ALTER TABLE ml.annotations
    ADD CONSTRAINT fk_annotations_finding
        FOREIGN KEY (finding_id)
        REFERENCES findings(id)
        ON DELETE SET NULL;

-- Foreign key: supersedes chain (self-referential).
ALTER TABLE ml.annotations
    ADD CONSTRAINT fk_annotations_supersedes
        FOREIGN KEY (supersedes_annotation_id)
        REFERENCES ml.annotations(id)
        ON DELETE SET NULL;

-- Database-level idempotency: identical repeated feedback is deduplicated.
-- The key is deterministic: finding_id + reviewer_user_id + feedback_action.
CREATE UNIQUE INDEX IF NOT EXISTS uq_annotations_idempotency_key
    ON ml.annotations(idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- Indexes for common query patterns.
CREATE INDEX IF NOT EXISTS idx_annotations_finding_id
    ON ml.annotations(finding_id);

CREATE INDEX IF NOT EXISTS idx_annotations_reviewer_user_id
    ON ml.annotations(reviewer_user_id);

CREATE INDEX IF NOT EXISTS idx_annotations_code_sample_id
    ON ml.annotations(code_sample_id);

CREATE INDEX IF NOT EXISTS idx_annotations_anti_pattern
    ON ml.annotations(anti_pattern_id);

CREATE INDEX IF NOT EXISTS idx_annotations_source
    ON ml.annotations(source);

CREATE INDEX IF NOT EXISTS idx_annotations_created_at
    ON ml.annotations(created_at DESC);
