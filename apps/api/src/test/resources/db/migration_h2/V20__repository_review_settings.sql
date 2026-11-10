-- V20 (H2 mirror): per-repository review settings. See db/migration/V20.
ALTER TABLE repositories ADD COLUMN IF NOT EXISTS review_enabled_rules VARCHAR(2000) DEFAULT '' NOT NULL;
ALTER TABLE repositories ADD COLUMN IF NOT EXISTS review_min_severity VARCHAR(10) DEFAULT 'minor' NOT NULL;
ALTER TABLE repositories ADD COLUMN IF NOT EXISTS review_min_confidence DECIMAL(4,3) DEFAULT 0 NOT NULL;
ALTER TABLE repositories ADD COLUMN IF NOT EXISTS review_post_comments BOOLEAN DEFAULT TRUE NOT NULL;
ALTER TABLE repositories ADD COLUMN IF NOT EXISTS review_inline_comments BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE repositories ADD COLUMN IF NOT EXISTS review_languages VARCHAR(100)
    DEFAULT 'python,javascript,typescript,java' NOT NULL;

ALTER TABLE repositories ADD CONSTRAINT chk_repo_review_min_severity
    CHECK (review_min_severity IN ('minor', 'major', 'critical'));
ALTER TABLE repositories ADD CONSTRAINT chk_repo_review_min_confidence
    CHECK (review_min_confidence >= 0 AND review_min_confidence <= 1);
ALTER TABLE repositories ADD CONSTRAINT chk_repo_review_enabled_rules
    CHECK (REGEXP_LIKE(review_enabled_rules, '^([A-Z0-9_]+(,[A-Z0-9_]+)*)?$'));
ALTER TABLE repositories ADD CONSTRAINT chk_repo_review_languages
    CHECK (REGEXP_LIKE(review_languages, '^(python|javascript|typescript|java)(,(python|javascript|typescript|java))*$'));
