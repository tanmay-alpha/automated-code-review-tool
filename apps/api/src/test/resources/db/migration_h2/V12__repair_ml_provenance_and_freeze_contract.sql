-- ============================================================
-- V12 (H2 mirror): repair ML provenance and trust level
-- ============================================================

ALTER TABLE ml.annotations ADD COLUMN IF NOT EXISTS trust_level VARCHAR(30);

UPDATE ml.annotations SET source = 'human' WHERE source = 'manual_annotation';

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

ALTER TABLE ml.annotations ALTER COLUMN trust_level SET DEFAULT 'finding_feedback';
