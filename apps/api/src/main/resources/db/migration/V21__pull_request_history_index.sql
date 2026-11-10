-- ============================================
-- V21: index for paginated PR history
-- ============================================
-- GET /api/repos/{id}/prs pages a repository's PRs newest first
-- (WHERE repo_id = ? ORDER BY created_at DESC LIMIT/OFFSET). With only
-- idx_prs_repo_id every page sorted all of the repository's PRs. The new
-- index serves the filter and the order; it also makes idx_prs_repo_id
-- redundant (uq_pr_repo covers the remaining repo_id lookups).

CREATE INDEX IF NOT EXISTS idx_prs_repo_created_at
    ON pull_requests(repo_id, created_at DESC);

DROP INDEX IF EXISTS idx_prs_repo_id;
