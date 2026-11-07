-- ============================================
-- V17: Sync anti_patterns with taxonomy/anti_patterns.yaml and drop
--      unused users columns.
-- ============================================
-- V5 seeded anti_patterns with IDs that never existed in the YAML and
-- missed three that do (PERFORMANCE_QUADRATIC_LOOP,
-- RELIABILITY_MISSING_TIMEOUT, MAINTAINABILITY_COMMENTED_CODE). This
-- migration makes the table match taxonomy/anti_patterns.yaml (v1.0.0)
-- exactly. findings.anti_pattern has no FK to this table, so historical
-- findings keep whatever ID they were stored with.

INSERT INTO anti_patterns (id, display_name, category, default_severity, trainable, description) VALUES
    ('SECURITY_HARDCODED_SECRET',      'Hardcoded Secret',   'SECURITY',        'critical', TRUE,  'A credential such as an API key, password, or token is hard-coded into the source tree.'),
    ('SECURITY_SQL_INJECTION',         'SQL Injection',      'SECURITY',        'major',    TRUE,  'User-controlled input is concatenated into a SQL string passed to execute() or similar.'),
    ('SECURITY_WEAK_CRYPTO',           'Weak Cryptography',  'SECURITY',        'major',    TRUE,  'Use of weak or broken cryptographic primitives (MD5, SHA-1, DES, RC4, Math.random, etc.).'),
    ('PERFORMANCE_N_PLUS_ONE',         'N+1 Query',          'PERFORMANCE',     'major',    TRUE,  'A loop performs a per-iteration database or remote call that should be batched.'),
    ('PERFORMANCE_QUADRATIC_LOOP',     'Quadratic Loop',     'PERFORMANCE',     'major',    TRUE,  'Nested iteration with O(n^2) or worse complexity.'),
    ('RELIABILITY_BROAD_EXCEPTION',    'Broad Exception',    'RELIABILITY',     'major',    TRUE,  'A bare except, catch-all Exception, or Java catch (Exception|Throwable) that swallows errors.'),
    ('RELIABILITY_MISSING_TIMEOUT',    'Missing Timeout',    'RELIABILITY',     'minor',    TRUE,  'External call (HTTP, DB, lock, queue) has no timeout.'),
    ('READABILITY_MAGIC_NUMBER',       'Magic Number',       'READABILITY',     'minor',    TRUE,  'Unexplained numeric literal in code.'),
    ('READABILITY_LONG_METHOD',        'Long Statement',     'READABILITY',     'minor',    TRUE,  'A single statement or line that exceeds reasonable length.'),
    ('MAINTAINABILITY_DUPLICATE_CODE', 'Duplicate Code',     'MAINTAINABILITY', 'minor',    TRUE,  'Identical or near-identical logic appears in multiple places.'),
    ('MAINTAINABILITY_COMMENTED_CODE', 'Commented-Out Code', 'MAINTAINABILITY', 'minor',    FALSE, 'Multi-line blocks of commented-out code.'),
    ('MAINTAINABILITY_PRINT_STATEMENT','Print Statement',    'MAINTAINABILITY', 'minor',    FALSE, 'A bare print() (Python) or console.log (JS/TS) remains in production code.')
ON CONFLICT (id) DO UPDATE SET
    display_name     = EXCLUDED.display_name,
    category         = EXCLUDED.category,
    default_severity = EXCLUDED.default_severity,
    trainable        = EXCLUDED.trainable,
    description      = EXCLUDED.description,
    updated_at       = NOW();

DELETE FROM anti_patterns WHERE id NOT IN (
    'SECURITY_HARDCODED_SECRET', 'SECURITY_SQL_INJECTION', 'SECURITY_WEAK_CRYPTO',
    'PERFORMANCE_N_PLUS_ONE', 'PERFORMANCE_QUADRATIC_LOOP',
    'RELIABILITY_BROAD_EXCEPTION', 'RELIABILITY_MISSING_TIMEOUT',
    'READABILITY_MAGIC_NUMBER', 'READABILITY_LONG_METHOD',
    'MAINTAINABILITY_DUPLICATE_CODE', 'MAINTAINABILITY_COMMENTED_CODE',
    'MAINTAINABILITY_PRINT_STATEMENT'
);

-- API keys live in api_keys (V3) and refresh-token state lives in Redis,
-- so these V1 columns are never read or written.
ALTER TABLE users
    DROP COLUMN IF EXISTS refresh_token,
    DROP COLUMN IF EXISTS api_key_hash,
    DROP COLUMN IF EXISTS api_key_prefix;
