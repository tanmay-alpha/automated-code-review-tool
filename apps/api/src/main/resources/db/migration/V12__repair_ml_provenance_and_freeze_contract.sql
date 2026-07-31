-- ============================================================
-- V12: repair ML provenance, trust-level, and freeze contract
-- ============================================================

-- 1. Trust level and source cleanup on annotations
ALTER TABLE ml.annotations
    ADD COLUMN IF NOT EXISTS trust_level VARCHAR(30);

UPDATE ml.annotations
   SET source = 'human'
 WHERE source = 'manual_annotation';

UPDATE ml.annotations
   SET trust_level = CASE
       WHEN source = 'human' THEN 'human_single'
       WHEN source = 'finding_feedback' THEN 'finding_feedback'
       WHEN source = 'import' THEN 'import'
       WHEN source = 'fallback' THEN 'fallback'
       WHEN source = 'model' THEN 'model'
       ELSE 'finding_feedback'
   END
 WHERE trust_level IS NULL;

ALTER TABLE ml.annotations
    ALTER COLUMN trust_level SET DEFAULT 'finding_feedback',
    ALTER COLUMN trust_level SET NOT NULL;

ALTER TABLE ml.annotations
    DROP CONSTRAINT IF EXISTS chk_annotations_trust_level;

ALTER TABLE ml.annotations
    ADD CONSTRAINT chk_annotations_trust_level
        CHECK (trust_level IN ('human_single', 'human_adjudicated', 'finding_feedback', 'import', 'fallback', 'model'));

ALTER TABLE ml.annotations
    DROP CONSTRAINT IF EXISTS chk_annotations_source;

ALTER TABLE ml.annotations
    ADD CONSTRAINT chk_annotations_source
        CHECK (source IN ('human', 'finding_feedback', 'import', 'fallback', 'model'));


-- 2. Repaired raise_on_frozen_dataset trigger function
CREATE OR REPLACE FUNCTION ml.raise_on_frozen_dataset()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    ds_status TEXT;
    target_dataset_id UUID;
    sample_id_to_check UUID;
BEGIN
    IF TG_TABLE_NAME = 'dataset_items' THEN
        target_dataset_id := COALESCE(NEW.dataset_version_id, OLD.dataset_version_id);
    ELSIF TG_TABLE_NAME = 'dataset_versions' THEN
        IF TG_OP = 'UPDATE' THEN
            -- Allow transition from draft -> frozen
            IF OLD.status = 'draft' AND NEW.status = 'frozen' THEN
                RETURN NEW;
            END IF;
            -- Block modification of frozen dataset (manifest, taxonomy, frozen_at, status back to draft)
            IF OLD.status = 'frozen' THEN
                IF NEW.status IS DISTINCT FROM OLD.status
                   OR NEW.manifest_hash IS DISTINCT FROM OLD.manifest_hash
                   OR NEW.taxonomy_version IS DISTINCT FROM OLD.taxonomy_version
                   OR NEW.frozen_at IS DISTINCT FROM OLD.frozen_at THEN
                    RAISE EXCEPTION 'Cannot modify a frozen dataset version: %', OLD.id;
                END IF;
            END IF;
        ELSIF TG_OP = 'DELETE' THEN
            IF OLD.status = 'frozen' THEN
                RAISE EXCEPTION 'Cannot delete a frozen dataset version: %', OLD.id;
            END IF;
        END IF;
        RETURN COALESCE(NEW, OLD);
    ELSIF TG_TABLE_NAME = 'annotations' THEN
        sample_id_to_check := COALESCE(NEW.code_sample_id, OLD.code_sample_id);
        SELECT di.dataset_version_id INTO target_dataset_id
          FROM ml.dataset_items di
         WHERE di.code_sample_id = sample_id_to_check
         LIMIT 1;
        IF target_dataset_id IS NULL THEN
            RETURN COALESCE(NEW, OLD);
        END IF;
    ELSIF TG_TABLE_NAME = 'code_samples' THEN
        sample_id_to_check := COALESCE(NEW.id, OLD.id);
        SELECT di.dataset_version_id INTO target_dataset_id
          FROM ml.dataset_items di
         WHERE di.code_sample_id = sample_id_to_check
         LIMIT 1;
        IF target_dataset_id IS NULL THEN
            RETURN COALESCE(NEW, OLD);
        END IF;
    ELSE
        RETURN COALESCE(NEW, OLD);
    END IF;

    IF target_dataset_id IS NOT NULL THEN
        SELECT dv.status INTO ds_status
          FROM ml.dataset_versions dv
         WHERE dv.id = target_dataset_id;

        IF ds_status = 'frozen' THEN
            RAISE EXCEPTION 'Cannot modify data belonging to frozen dataset: %', target_dataset_id;
        END IF;
    END IF;

    RETURN COALESCE(NEW, OLD);
END;
$$;
