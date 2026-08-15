-- Repair the ML dataset contract without changing historical migrations.

ALTER TABLE ml.code_samples
    ADD COLUMN IF NOT EXISTS hunk_sha256 VARCHAR(64);

-- Existing raw_hunk values are already redacted, so legacy rows can only be
-- backfilled from that safe representation. New ingestion computes this
-- structural digest before redaction and persists only the digest plus the
-- redacted hunk. Canonical bytes use LF endings and omit trailing newlines.
UPDATE ml.code_samples
   SET hunk_sha256 = encode(
       digest(
           convert_to(
               regexp_replace(
                   replace(replace(raw_hunk, E'\r\n', E'\n'), E'\r', E'\n'),
                   E'\n+$',
                   ''
               ),
               'UTF8'
           ),
           'sha256'
       ),
       'hex'
   )
 WHERE hunk_sha256 IS NULL;

ALTER TABLE ml.code_samples
    ALTER COLUMN hunk_sha256 SET NOT NULL;

ALTER TABLE ml.code_samples
    ADD CONSTRAINT chk_code_samples_hunk_sha256
        CHECK (hunk_sha256 ~ '^[0-9a-f]{64}$');

ALTER TABLE ml.code_samples
    ADD CONSTRAINT chk_code_samples_content_sha256
        CHECK (content_sha256 ~ '^[0-9a-f]{64}$');

CREATE INDEX IF NOT EXISTS idx_code_samples_hunk_sha256
    ON ml.code_samples(hunk_sha256);

COMMENT ON COLUMN ml.code_samples.hunk_sha256 IS
    'Lowercase SHA-256 hex of the UTF-8 unified-diff hunk before redaction: @@ header plus body, LF line endings, no trailing LF; path excluded. Raw persisted content remains redacted.';

-- A sample can belong to several dataset versions. Mutation must be blocked
-- when any membership is frozen, not whichever membership happens to be read
-- first. TG_OP branches also avoid referencing NEW during DELETE triggers.
CREATE OR REPLACE FUNCTION ml.raise_on_frozen_dataset()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_sample_id UUID;
BEGIN
    IF TG_TABLE_NAME = 'dataset_versions' THEN
        IF TG_OP IN ('UPDATE', 'DELETE') AND OLD.status = 'frozen' THEN
            RAISE EXCEPTION 'Cannot modify a frozen dataset version: %', OLD.id;
        END IF;
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;

    IF TG_TABLE_NAME = 'dataset_items' THEN
        IF TG_OP IN ('UPDATE', 'DELETE') AND EXISTS (
            SELECT 1
              FROM ml.dataset_versions dv
             WHERE dv.id = OLD.dataset_version_id
               AND dv.status = 'frozen'
        ) THEN
            RAISE EXCEPTION 'Cannot modify data belonging to frozen dataset: %', OLD.dataset_version_id;
        END IF;
        IF TG_OP IN ('INSERT', 'UPDATE') AND EXISTS (
            SELECT 1
              FROM ml.dataset_versions dv
             WHERE dv.id = NEW.dataset_version_id
               AND dv.status = 'frozen'
        ) THEN
            RAISE EXCEPTION 'Cannot modify data belonging to frozen dataset: %', NEW.dataset_version_id;
        END IF;
    ELSIF TG_TABLE_NAME = 'annotations' THEN
        IF TG_OP IN ('UPDATE', 'DELETE') THEN
            target_sample_id := OLD.code_sample_id;
        END IF;
    ELSIF TG_TABLE_NAME = 'code_samples' THEN
        IF TG_OP IN ('UPDATE', 'DELETE') THEN
            target_sample_id := OLD.id;
        END IF;
    END IF;

    IF target_sample_id IS NOT NULL AND EXISTS (
        SELECT 1
          FROM ml.dataset_items di
          JOIN ml.dataset_versions dv ON dv.id = di.dataset_version_id
         WHERE di.code_sample_id = target_sample_id
           AND dv.status = 'frozen'
    ) THEN
        RAISE EXCEPTION 'Cannot modify a sample belonging to a frozen dataset: %', target_sample_id;
    END IF;

    IF TG_TABLE_NAME = 'annotations' AND TG_OP IN ('INSERT', 'UPDATE') THEN
        target_sample_id := NEW.code_sample_id;
    ELSIF TG_TABLE_NAME = 'code_samples' AND TG_OP IN ('INSERT', 'UPDATE') THEN
        target_sample_id := NEW.id;
    ELSE
        target_sample_id := NULL;
    END IF;

    IF target_sample_id IS NOT NULL AND EXISTS (
        SELECT 1
          FROM ml.dataset_items di
          JOIN ml.dataset_versions dv ON dv.id = di.dataset_version_id
         WHERE di.code_sample_id = target_sample_id
           AND dv.status = 'frozen'
    ) THEN
        RAISE EXCEPTION 'Cannot modify a sample belonging to a frozen dataset: %', target_sample_id;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_dataset_versions_immutable ON ml.dataset_versions;
CREATE TRIGGER trg_dataset_versions_immutable
    BEFORE UPDATE OR DELETE ON ml.dataset_versions
    FOR EACH ROW EXECUTE FUNCTION ml.raise_on_frozen_dataset();

DROP TRIGGER IF EXISTS trg_dataset_items_immutable ON ml.dataset_items;
CREATE TRIGGER trg_dataset_items_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON ml.dataset_items
    FOR EACH ROW EXECUTE FUNCTION ml.raise_on_frozen_dataset();

DROP TRIGGER IF EXISTS trg_annotations_immutable ON ml.annotations;
CREATE TRIGGER trg_annotations_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON ml.annotations
    FOR EACH ROW EXECUTE FUNCTION ml.raise_on_frozen_dataset();

DROP TRIGGER IF EXISTS trg_code_samples_immutable ON ml.code_samples;
CREATE TRIGGER trg_code_samples_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON ml.code_samples
    FOR EACH ROW EXECUTE FUNCTION ml.raise_on_frozen_dataset();
