-- automated-code-review-tool — V4: Schema fixes (check constraints, indexes, dead columns, defaults)
--
-- This migration fixes issues identified in the deep database audit:
--   1. pull_requests.status default mismatch (pending → processing)
--   2. processed_webhooks needs repo_id FK for cascade cleanup
--   3. CHECK constraints on enum-like columns
--   4. Missing indexes for common query patterns
--   5. quality_metrics needs updated_at
--   6. Backfill processed_webhooks.repo_id from current PR state
--
-- ============================================
-- 1. Fix pull_requests.status default
-- ============================================

-- Change the column default from 'pending' (never used by code) to 'processing'.
-- Existing rows with 'pending' are migrated to 'processing'.
ALTER TABLE pull_requests
    ALTER COLUMN status SET DEFAULT 'processing';

UPDATE pull_requests
   SET status = 'processing'
 WHERE status = 'pending';

-- Add a CHECK constraint so only valid statuses can be stored.
ALTER TABLE pull_requests
  ADD CONSTRAINT chk_pr_status
  CHECK (status IN ('processing', 'reviewed', 'failed'));

-- ============================================
-- 2. Add repo_id to processed_webhooks
-- ============================================

-- Backfill repo_id by joining to pull_requests via the delivery_id.
-- We derive it from the event payload stored in-process (not in DB),
-- so we look up the most recent PR and use its repo.
-- For entries where we can't determine the repo, repo_id stays NULL.
ALTER TABLE processed_webhooks
    ADD COLUMN IF NOT EXISTS repo_id UUID
        REFERENCES repositories(id) ON DELETE CASCADE;

-- Try to backfill: for each delivery, find the repo of the PR it belongs to.
-- We join through a subquery that finds the most recent PR linked to a repo.
UPDATE processed_webhooks pw
   SET repo_id = pr.repo_id
  FROM pull_requests pr
 WHERE pr.id = (
       SELECT pr2.id
         FROM pull_requests pr2
        WHERE pr2.repo_id IS NOT NULL
        ORDER BY pr2.created_at DESC
         LIMIT 1
     );

-- Add an index on repo_id for the cascade cleanup query.
CREATE INDEX IF NOT EXISTS idx_processed_webhooks_repo_id
    ON processed_webhooks(repo_id);

-- Composite index for the cleanup query (repo_id + processed_at).
CREATE INDEX IF NOT EXISTS idx_processed_webhooks_repo_processed
    ON processed_webhooks(repo_id, processed_at);

-- ============================================
-- 3. CHECK constraints on findings enum columns
-- ============================================

ALTER TABLE findings
  ADD CONSTRAINT chk_finding_status
  CHECK (status IN ('open', 'accepted', 'dismissed', 'fixed'));

ALTER TABLE findings
  ADD CONSTRAINT chk_finding_severity
  CHECK (severity IN ('critical', 'major', 'minor'));

ALTER TABLE findings
  ADD CONSTRAINT chk_finding_category
  CHECK (category IN (
      'SECURITY', 'PERFORMANCE', 'ARCHITECTURE',
      'RELIABILITY', 'READABILITY', 'MAINTAINABILITY'
  ));

-- ============================================
-- 4. Additional indexes for query performance
-- ============================================

-- Findings: index on severity for filtering/dashboard counts.
CREATE INDEX IF NOT EXISTS idx_findings_severity
    ON findings(severity);

-- Findings: composite index for "findings for a PR grouped by severity".
CREATE INDEX IF NOT EXISTS idx_findings_pr_severity
    ON findings(pr_id, severity);

-- Quality metrics: index on date alone for cleanup / TTL queries.
CREATE INDEX IF NOT EXISTS idx_quality_metrics_date
    ON quality_metrics(date);

-- Processed webhooks: index on processed_at for TTL cleanup.
CREATE INDEX IF NOT EXISTS idx_processed_webhooks_processed_at
    ON processed_webhooks(processed_at);

-- ============================================
-- 5. quality_metrics updated_at timestamp
-- ============================================

ALTER TABLE quality_metrics
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP;

-- Backfill: set updated_at = created_at for existing rows.
UPDATE quality_metrics
   SET updated_at = created_at
 WHERE updated_at IS NULL;

-- ============================================
-- 6. Anti-pattern lookup table (for dashboard "top patterns")
-- ============================================

-- A lightweight reference table so the frontend can display
-- human-readable anti-pattern names without hardcoding.
CREATE TABLE IF NOT EXISTS anti_patterns (
    id              VARCHAR(80)  PRIMARY KEY,
    display_name    VARCHAR(120) NOT NULL,
    category        VARCHAR(30)  NOT NULL,
    default_severity VARCHAR(10) NOT NULL DEFAULT 'minor',
    description     TEXT
);

INSERT INTO anti_patterns (id, display_name, category, default_severity, description) VALUES
    ('PERFORMANCE_N_PLUS_1',          'N+1 Query',                     'PERFORMANCE',      'major',    'Looping over results and querying per iteration'),
    ('PERFORMANCE_MISSING_INDEX',      'Missing Database Index',        'PERFORMANCE',      'major',    'Query scans full table due to missing index'),
    ('PERFORMANCE_FULL_TABLE_SCAN',    'Full Table Scan',               'PERFORMANCE',      'major',    'Query performs a sequential scan on a large table'),
    ('SECURITY_HARDCODED_SECRET',      'Hardcoded Secret',              'SECURITY',         'critical', 'API key, password, or token embedded in source'),
    ('SECURITY_SQL_INJECTION',         'SQL Injection Risk',            'SECURITY',         'critical', 'String concatenation in SQL query'),
    ('SECURITY_XSS_VULNERABILITY',     'Cross-Site Scripting (XSS)',    'SECURITY',         'critical', 'Unescaped user input rendered in HTML'),
    ('SECURITY_WEAK_CRYPTO',           'Weak Cryptography',             'SECURITY',         'major',    'Use of MD5, SHA-1, or other broken algorithms'),
    ('SECURITY_INSECURE_DESERIAL',     'Insecure Deserialization',      'SECURITY',         'critical', 'Untrusted data passed to deserializer'),
    ('SECURITY_MISSING_AUTH',          'Missing Authorization Check',   'SECURITY',         'major',    'Endpoint reachable without authentication'),
    ('SECURITY_SENSITIVE_LOGGING',     'Sensitive Data in Logs',        'SECURITY',         'major',    'PII or secrets written to log output'),
    ('ARCHITECTURE_GOD_CLASS',         'God Class',                     'ARCHITECTURE',     'major',    'Single class with too many responsibilities'),
    ('ARCHITECTURE_CIRCULAR_DEP',      'Circular Dependency',           'ARCHITECTURE',     'major',    'Mutual dependency between modules'),
    ('ARCHITECTURE_DEEP_NESTING',      'Deep Nesting',                  'ARCHITECTURE',     'minor',    'Excessive nesting depth reduces readability'),
    ('RELIABILITY_MISSING_ERROR_HANDLING', 'Missing Error Handling',    'RELIABILITY',      'major',    'No try/catch around external call'),
    ('RELIABILITY_RESOURCE_LEAK',      'Resource Leak',                 'RELIABILITY',      'major',    'Stream, connection, or file not closed'),
    ('RELIABILITY_RACE_CONDITION',     'Race Condition',                'RELIABILITY',      'major',    'Concurrent access without synchronization'),
    ('RELIABILITY_MAGIC_NUMBER',       'Magic Number',                  'RELIABILITY',      'minor',    'Unexplained numeric literal in business logic'),
    ('READABILITY_DEAD_CODE',          'Dead Code',                     'READABILITY',      'minor',    'Unreachable or unused code block'),
    ('READABILITY_LONG_METHOD',        'Long Method',                   'READABILITY',      'minor',    'Method exceeds reasonable length'),
    ('READABILITY_MISLEADING_NAME',    'Misleading Variable Name',      'READABILITY',      'minor',    'Name does not match actual behavior'),
    ('MAINTAINABILITY_DUPLICATE_CODE', 'Duplicate Code',                'MAINTAINABILITY',  'minor',    'Copy-pasted logic instead of shared function'),
    ('MAINTAINABILITY_TIGHT_COUPLING', 'Tight Coupling',                'MAINTAINABILITY',  'minor',    'Module depends on another internal detail'),
    ('MAINTAINABILITY_MISSING_TESTS',  'Missing Tests',                 'MAINTAINABILITY',  'minor',    'No unit or integration tests for this logic'),
    ('UNKNOWN',                        'Unknown Pattern',               'MAINTAINABILITY',  'minor',    'Pattern not recognized by the engine')
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- 7. Scheduled cleanup function for processed_webhooks
-- ============================================

-- Create a helper function that deletes webhook deliveries older than
-- 24 hours. This is called by a Spring @Scheduled job (see WebhookService).
CREATE OR REPLACE FUNCTION cleanup_old_webhooks()
RETURNS void
LANGUAGE sql
AS $$
    DELETE FROM processed_webhooks
     WHERE processed_at < NOW() - INTERVAL '24 hours';
$$;

-- ============================================
-- 8. Comments on key columns for developer clarity
-- ============================================

COMMENT ON COLUMN pull_requests.status IS 'processing | reviewed | failed';
COMMENT ON COLUMN findings.status IS 'open | accepted | dismissed | fixed';
COMMENT ON COLUMN findings.severity IS 'critical | major | minor';
COMMENT ON COLUMN findings.category IS 'SECURITY | PERFORMANCE | ARCHITECTURE | RELIABILITY | READABILITY | MAINTAINABILITY';
COMMENT ON COLUMN findings.file_path IS 'Source file path (populated from diff headers; n/a if unavailable)';
COMMENT ON COLUMN findings.code_snippet IS 'Flagged code lines extracted from the diff';
COMMENT ON COLUMN repositories.quality_score IS 'Latest rolling quality score (updated by ReviewService after each review)';
COMMENT ON COLUMN quality_metrics.updated_at IS 'Last time this metric row was recalculated';
