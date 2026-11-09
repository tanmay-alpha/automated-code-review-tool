-- ============================================
-- V20: per-repository review settings
-- ============================================
-- Read and written through GET/PUT /api/repos/{id}/settings and applied to
-- worker findings by RepoReviewPolicy. Lists are stored as comma-separated
-- identifiers (both vocabularies are [a-zA-Z0-9_]); the API validates rule
-- IDs against anti_patterns at write time. findings.anti_pattern has no FK
-- to anti_patterns, so a later taxonomy change cannot break stored settings.

ALTER TABLE repositories
    ADD COLUMN IF NOT EXISTS review_enabled_rules   VARCHAR(2000) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS review_min_severity    VARCHAR(10)   NOT NULL DEFAULT 'minor',
    ADD COLUMN IF NOT EXISTS review_min_confidence  DECIMAL(4,3)  NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS review_post_comments   BOOLEAN       NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS review_inline_comments BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS review_languages       VARCHAR(100)  NOT NULL
        DEFAULT 'python,javascript,typescript,java';

ALTER TABLE repositories
    ADD CONSTRAINT chk_repo_review_min_severity
        CHECK (review_min_severity IN ('minor', 'major', 'critical')),
    ADD CONSTRAINT chk_repo_review_min_confidence
        CHECK (review_min_confidence >= 0 AND review_min_confidence <= 1),
    ADD CONSTRAINT chk_repo_review_enabled_rules
        CHECK (review_enabled_rules ~ '^([A-Z0-9_]+(,[A-Z0-9_]+)*)?$'),
    ADD CONSTRAINT chk_repo_review_languages
        CHECK (review_languages ~ '^(python|javascript|typescript|java)(,(python|javascript|typescript|java))*$');

COMMENT ON COLUMN repositories.review_enabled_rules IS 'Comma-separated anti_patterns ids; empty = every rule';
COMMENT ON COLUMN repositories.review_languages IS 'Comma-separated subset of python,javascript,typescript,java';
