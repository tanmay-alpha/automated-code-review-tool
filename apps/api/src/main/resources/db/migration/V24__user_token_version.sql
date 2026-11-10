-- ============================================
-- V24: Per-user session version for "sign out everywhere"
-- ============================================
-- Every issued JWT carries the user's token_version as its "ver" claim;
-- bumping the column revokes all access and refresh tokens at once.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS token_version INTEGER NOT NULL DEFAULT 0;
