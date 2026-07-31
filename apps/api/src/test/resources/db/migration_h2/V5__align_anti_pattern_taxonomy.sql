-- ============================================
-- V5 (H2 mirror): align anti_pattern taxonomy
-- ============================================
-- H2 does not support `ALTER TABLE ... TYPE USING`,
-- but the original Postgres migration is already idempotent
-- data-only DML — we replicate the trimmed value set here.

UPDATE findings
SET anti_pattern = 'LONG_METHOD'
WHERE anti_pattern IN ('LONG_FUNCTION', 'TOO_LONG', 'LONG_FUNC');
