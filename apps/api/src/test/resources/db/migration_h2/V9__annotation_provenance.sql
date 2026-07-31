-- ============================================
-- V9 (H2 mirror): reviewer-aware annotation provenance
-- ============================================

ALTER TABLE annotations ADD COLUMN IF NOT EXISTS finding_id UUID;
ALTER TABLE annotations ADD COLUMN IF NOT EXISTS reviewer_user_id UUID;
ALTER TABLE annotations ADD COLUMN IF NOT EXISTS feedback_action VARCHAR(20);
ALTER TABLE annotations ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(100);
ALTER TABLE annotations ADD COLUMN IF NOT EXISTS supersedes_annotation_id UUID;
ALTER TABLE annotations ADD COLUMN IF NOT EXISTS resolution_state VARCHAR(20) NOT NULL DEFAULT 'active';

CREATE INDEX IF NOT EXISTS uq_annotations_idempotency_key
    ON annotations(idempotency_key);

CREATE INDEX IF NOT EXISTS idx_annotations_finding_id ON annotations(finding_id);
CREATE INDEX IF NOT EXISTS idx_annotations_reviewer_user_id ON annotations(reviewer_user_id);
