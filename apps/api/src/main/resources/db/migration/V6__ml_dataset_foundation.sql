-- ============================================
-- V6: ML dataset foundation schema
-- ============================================
-- Introduces a dedicated `ml` schema for machine-learning
-- data-plane tables so they cannot clash with the operational
-- `public` schema used by the API.
--
-- Tables:
--   ml.code_samples       — one model-ready code-change unit (a PR hunk)
--   ml.annotations        — labels attached to code samples
--   ml.dataset_versions   — immutable dataset releases
--   ml.dataset_items      — connect samples to a dataset version + split
--
-- Safety: every migration is IF NOT EXISTS / IF NOT EXISTS guard;
-- re-running V6 on a database already past V6 is idempotent.
-- ============================================

-- ============================================
-- Schema
-- ============================================
CREATE SCHEMA IF NOT EXISTS ml;

-- ============================================
-- 1. ml.code_samples
-- ============================================
-- Represents a single PR hunk (or an equivalent unit) after
-- redaction. The content_sha256 + uniqueness constraint prevents
-- duplicate ingestion from repeated webhooks.
CREATE TABLE IF NOT EXISTS ml.code_samples (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid(),
    repository_id       UUID         NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    pull_request_id     UUID         NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE,
    commit_sha          VARCHAR(40)  NOT NULL,
    file_path           TEXT         NOT NULL,
    language            VARCHAR(30)  NOT NULL,
    old_start           INT          NOT NULL,
    old_count           INT          NOT NULL,
    new_start           INT          NOT NULL,
    new_count           INT          NOT NULL,
    raw_hunk            TEXT         NOT NULL,
    added_code          TEXT,
    context_code        TEXT,
    content_sha256      CHAR(64)     NOT NULL,
    group_key           VARCHAR(255) NOT NULL,
    source_type         VARCHAR(30)  NOT NULL,
    redaction_version   VARCHAR(30)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_code_sample_source_type
        CHECK (source_type IN ('pr_diff', 'file_scan', 'import')),
    CONSTRAINT chk_code_sample_language
        CHECK (language IN (
            'python', 'java', 'javascript', 'typescript', 'go',
            'rust', 'c', 'cpp', 'csharp', 'ruby', 'php', 'unknown'
        )),
    CONSTRAINT chk_code_sample_redaction
        CHECK (redaction_version IN ('v1')),

    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_code_samples_repo
    ON ml.code_samples(repository_id);
CREATE INDEX IF NOT EXISTS idx_code_samples_pr
    ON ml.code_samples(pull_request_id);
CREATE INDEX IF NOT EXISTS idx_code_samples_content_sha
    ON ml.code_samples(content_sha256);
CREATE INDEX IF NOT EXISTS idx_code_samples_group_key
    ON ml.code_samples(group_key);
CREATE INDEX IF NOT EXISTS idx_code_samples_created_at
    ON ml.code_samples(created_at DESC);

-- Uniqueness: same hunk re-ingested via multiple webhooks must not
-- produce duplicates.
CREATE UNIQUE INDEX IF NOT EXISTS uq_code_samples_pr_commit_file_start_hash
    ON ml.code_samples(pull_request_id, commit_sha, file_path, new_start, content_sha256);

COMMENT ON TABLE ml.code_samples IS
    'One model-ready code-change unit, normally one PR diff hunk.';
COMMENT ON COLUMN ml.code_samples.content_sha256 IS
    'SHA-256 of added_code (or raw_hunk when added_code is NULL).';
COMMENT ON COLUMN ml.code_samples.group_key IS
    'Derived from repository_id || ":" || pull_request_id for grouped splitting.';
COMMENT ON COLUMN ml.code_samples.redaction_version IS
    'Version of the secret-redaction pipeline applied before storage.';


-- ============================================
-- 2. ml.annotations
-- ============================================
-- Labels attached to code samples. One sample can carry many
-- annotations from different sources (human, fallback, model).
CREATE TABLE IF NOT EXISTS ml.annotations (
    id                UUID         NOT NULL DEFAULT gen_random_uuid(),
    code_sample_id    UUID         NOT NULL REFERENCES ml.code_samples(id) ON DELETE CASCADE,
    anti_pattern_id   VARCHAR(80)  NOT NULL,
    label_state       VARCHAR(20)  NOT NULL,
    line_start        INT          NOT NULL,
    line_end          INT          NOT NULL,
    source            VARCHAR(30)  NOT NULL,
    confidence        DECIMAL(5,4),
    reviewer_user_id  UUID,
    rationale         TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_annotation_label_state
        CHECK (label_state IN ('positive', 'negative', 'uncertain')),
    CONSTRAINT chk_annotation_source
        CHECK (source IN ('human', 'fallback', 'model', 'import', 'finding_feedback')),
    CONSTRAINT chk_annotation_confidence
        CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),

    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_annotations_sample
    ON ml.annotations(code_sample_id);
CREATE INDEX IF NOT EXISTS idx_annotations_anti_pattern
    ON ml.annotations(anti_pattern_id);
CREATE INDEX IF NOT EXISTS idx_annotations_source_state
    ON ml.annotations(source, label_state);

COMMENT ON TABLE ml.annotations IS
    'Labels attached to code samples. Negative annotations are used as hard negatives during training.';


-- ============================================
-- 3. ml.dataset_versions
-- ============================================
-- Immutable snapshot of a curated dataset. Once frozen no
-- code_samples, annotations or dataset_items may be inserted
-- against it.
CREATE TABLE IF NOT EXISTS ml.dataset_versions (
    id                       UUID         NOT NULL DEFAULT gen_random_uuid(),
    name                     VARCHAR(120) NOT NULL,
    version                  VARCHAR(40)  NOT NULL,
    taxonomy_version         VARCHAR(40)  NOT NULL,
    status                   VARCHAR(20)  NOT NULL DEFAULT 'draft',
    generation_config        JSONB        NOT NULL DEFAULT '{}',
    manifest_sha256          CHAR(64)     NOT NULL,
    sample_count             INT          NOT NULL DEFAULT 0,
    positive_annotation_count INT         NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    frozen_at                TIMESTAMPTZ,

    CONSTRAINT chk_dataset_status
        CHECK (status IN ('draft', 'frozen', 'deprecated')),

    PRIMARY KEY (id)
);

-- A dataset is identified by (name, version) pair.
CREATE UNIQUE INDEX IF NOT EXISTS uq_dataset_versions_name_version
    ON ml.dataset_versions(name, version);

CREATE INDEX IF NOT EXISTS idx_dataset_versions_status
    ON ml.dataset_versions(status);

COMMENT ON TABLE ml.dataset_versions IS
    'Immutable dataset release. Once frozen it is read-only.';


-- ============================================
-- 4. ml.dataset_items
-- ============================================
-- Assigns code_samples to a dataset version with a split.
-- Composite primary key prevents the same sample appearing in
-- the same dataset twice.
CREATE TABLE IF NOT EXISTS ml.dataset_items (
    dataset_version_id  UUID        NOT NULL REFERENCES ml.dataset_versions(id) ON DELETE CASCADE,
    code_sample_id      UUID        NOT NULL REFERENCES ml.code_samples(id) ON DELETE CASCADE,
    split               VARCHAR(10) NOT NULL,
    group_key           VARCHAR(255) NOT NULL,
    labels_snapshot     JSONB       NOT NULL DEFAULT '[]',

    CONSTRAINT chk_dataset_item_split
        CHECK (split IN ('train', 'validation', 'test')),

    PRIMARY KEY (dataset_version_id, code_sample_id)
);

CREATE INDEX IF NOT EXISTS idx_dataset_items_split
    ON ml.dataset_items(dataset_version_id, split);

COMMENT ON TABLE ml.dataset_items IS
    'Assigns a code_sample to a dataset version with a train/validation/test split.';

-- ============================================
-- 5. Trigger: prevent updates after freeze
-- ============================================
-- After a dataset_versions row is frozen, no code_sample,
-- annotation, or dataset_item linked to it may change. Enforced
-- via triggers that raise an exception when a frozen dataset
-- is modified.
CREATE OR REPLACE FUNCTION ml.raise_on_frozen_dataset()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    ds_status TEXT;
BEGIN
    IF TG_TABLE_NAME = 'dataset_items' THEN
        SELECT dv.status INTO ds_status
          FROM ml.dataset_versions dv
         WHERE dv.id = NEW.dataset_version_id;
    ELSIF TG_TABLE_NAME = 'dataset_versions' THEN
        IF OLD.status = 'frozen' AND OLD.status IS DISTINCT FROM NEW.status THEN
            RAISE EXCEPTION 'Cannot modify a frozen dataset version: %', OLD.id;
        END IF;
        RETURN NEW;
    END IF;
    IF ds_status = 'frozen' THEN
        RAISE EXCEPTION 'Cannot modify data belonging to frozen dataset: %', NEW.dataset_version_id;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_dataset_items_immutable ON ml.dataset_items;
CREATE TRIGGER trg_dataset_items_immutable
    BEFORE UPDATE OR DELETE ON ml.dataset_items
    FOR EACH ROW EXECUTE FUNCTION ml.raise_on_frozen_dataset();

DROP TRIGGER IF EXISTS trg_dataset_versions_immutable ON ml.dataset_versions;
CREATE TRIGGER trg_dataset_versions_immutable
    BEFORE UPDATE ON ml.dataset_versions
    FOR EACH ROW EXECUTE FUNCTION ml.raise_on_frozen_dataset();