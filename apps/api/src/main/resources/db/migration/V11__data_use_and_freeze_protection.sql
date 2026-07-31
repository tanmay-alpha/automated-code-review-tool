-- ============================================
-- V11: data-use provenance + freeze protection
-- ============================================
--
-- 1. Adds data-use, licence, and consent fields to repositories.
-- 2. Adds visibility, licence, and data-use fields to code_samples.
-- 3. Strengthens freeze protection to block INSERT in addition
--    to UPDATE/DELETE on dataset_items, annotations, and
--    code_samples when the linked dataset version is frozen.
-- ============================================

-- ============================================
-- 1. repositories: licence + consent
-- ============================================
ALTER TABLE repositories
    ADD COLUMN IF NOT EXISTS license_spdx VARCHAR(30);

ALTER TABLE repositories
    ADD COLUMN IF NOT EXISTS data_use_status VARCHAR(40) NOT NULL DEFAULT 'quarantined_unknown_license';

ALTER TABLE repositories
    ADD COLUMN IF NOT EXISTS consent_recorded_at TIMESTAMPTZ NULL;

ALTER TABLE repositories
    ADD CONSTRAINT chk_repo_data_use_status
        CHECK (data_use_status IN (
            'allowed_public',
            'allowed_owner_consent',
            'quarantined_unknown_license',
            'blocked_private_no_consent',
            'blocked_policy'
        ));

CREATE INDEX IF NOT EXISTS idx_repos_data_use_status
    ON repositories(data_use_status);


-- ============================================
-- 2. code_samples: licence + data-use
-- ============================================
ALTER TABLE ml.code_samples
    ADD COLUMN IF NOT EXISTS repository_visibility VARCHAR(20) NOT NULL DEFAULT 'private';

ALTER TABLE ml.code_samples
    ADD COLUMN IF NOT EXISTS license_spdx VARCHAR(30);

ALTER TABLE ml.code_samples
    ADD COLUMN IF NOT EXISTS data_use_status VARCHAR(40) NOT NULL DEFAULT 'quarantined_unknown_license';

ALTER TABLE ml.code_samples
    ADD COLUMN IF NOT EXISTS source_url TEXT;

ALTER TABLE ml.code_samples
    ADD CONSTRAINT chk_code_sample_visibility
        CHECK (repository_visibility IN ('public', 'private', 'internal'));

ALTER TABLE ml.code_samples
    ADD CONSTRAINT chk_code_sample_data_use
        CHECK (data_use_status IN (
            'allowed_public',
            'allowed_owner_consent',
            'quarantined_unknown_license',
            'blocked_private_no_consent',
            'blocked_policy'
        ));

CREATE INDEX IF NOT EXISTS idx_code_samples_data_use
    ON ml.code_samples(data_use_status);

CREATE INDEX IF NOT EXISTS idx_code_samples_visibility
    ON ml.code_samples(repository_visibility);


-- ============================================
-- 3. Strengthened freeze protection
-- ============================================
-- Extend the existing trigger function to also block INSERT.
CREATE OR REPLACE FUNCTION ml.raise_on_frozen_dataset()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    ds_status TEXT;
    target_dataset_id UUID;
BEGIN
    -- Determine the dataset_version_id from the row being inserted/updated/deleted.
    IF TG_TABLE_NAME = 'dataset_items' THEN
        target_dataset_id := NEW.dataset_version_id;
    ELSIF TG_TABLE_NAME = 'dataset_versions' THEN
        -- Allow status transitions to 'frozen'; block everything else on frozen rows.
        IF OLD.status = 'frozen' AND OLD.status IS DISTINCT FROM NEW.status THEN
            RAISE EXCEPTION 'Cannot modify a frozen dataset version: %', OLD.id;
        END IF;
        RETURN NEW;
    ELSIF TG_TABLE_NAME IN ('annotations', 'code_samples') THEN
        -- For annotations/code_samples, look up the dataset version via dataset_items.
        SELECT di.dataset_version_id INTO target_dataset_id
          FROM ml.dataset_items di
         WHERE di.code_sample_id = NEW.code_sample_id
         LIMIT 1;
        IF target_dataset_id IS NULL THEN
            RETURN NEW; -- Not linked to any dataset yet.
        END IF;
    ELSE
        RETURN NEW;
    END IF;

    SELECT dv.status INTO ds_status
      FROM ml.dataset_versions dv
     WHERE dv.id = target_dataset_id;

    IF ds_status = 'frozen' THEN
        RAISE EXCEPTION 'Cannot modify data belonging to frozen dataset: %', target_dataset_id;
    END IF;
    RETURN NEW;
END;
$$;

-- Re-create triggers to cover INSERT as well.
DROP TRIGGER IF EXISTS trg_dataset_items_immutable ON ml.dataset_items;
CREATE TRIGGER trg_dataset_items_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON ml.dataset_items
    FOR EACH ROW EXECUTE FUNCTION ml.raise_on_frozen_dataset();

DROP TRIGGER IF EXISTS trg_dataset_versions_immutable ON ml.dataset_versions;
CREATE TRIGGER trg_dataset_versions_immutable
    BEFORE UPDATE ON ml.dataset_versions
    FOR EACH ROW EXECUTE FUNCTION ml.raise_on_frozen_dataset();

DROP TRIGGER IF EXISTS trg_annotations_immutable ON ml.annotations;
CREATE TRIGGER trg_annotations_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON ml.annotations
    FOR EACH ROW EXECUTE FUNCTION ml.raise_on_frozen_dataset();

DROP TRIGGER IF EXISTS trg_code_samples_immutable ON ml.code_samples;
CREATE TRIGGER trg_code_samples_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON ml.code_samples
    FOR EACH ROW EXECUTE FUNCTION ml.raise_on_frozen_dataset();
