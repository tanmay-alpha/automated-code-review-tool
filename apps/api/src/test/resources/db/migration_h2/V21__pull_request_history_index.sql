-- V21 (H2 mirror): index for paginated PR history. See db/migration/V21.
-- idx_prs_repo_id is kept here: H2 uses it to back the repo_id foreign key.
CREATE INDEX IF NOT EXISTS idx_prs_repo_created_at ON pull_requests(repo_id, created_at DESC);
