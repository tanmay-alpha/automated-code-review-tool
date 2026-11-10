-- ============================================
-- V24 (H2 mirror): Per-user session version for "sign out everywhere"
-- ============================================
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS token_version INTEGER NOT NULL DEFAULT 0;
