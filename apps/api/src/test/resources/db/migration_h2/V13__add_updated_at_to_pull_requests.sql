-- ============================================
-- V13 (H2 mirror): Add updated_at column to pull_requests
-- ============================================
ALTER TABLE pull_requests
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
