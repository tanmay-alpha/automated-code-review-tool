-- ============================================
-- V13: Add updated_at column to pull_requests
-- ============================================
ALTER TABLE pull_requests
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW();
