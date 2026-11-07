-- ============================================
-- V17 (H2 mirror): sync anti_patterns with taxonomy/anti_patterns.yaml
-- and drop unused users columns.
-- ============================================

MERGE INTO anti_patterns (id, display_name, category, default_severity, trainable, description, updated_at) KEY(id) VALUES
    ('SECURITY_HARDCODED_SECRET',      'Hardcoded Secret',   'SECURITY',        'critical', TRUE,  'A credential such as an API key, password, or token is hard-coded into the source tree.', CURRENT_TIMESTAMP),
    ('SECURITY_SQL_INJECTION',         'SQL Injection',      'SECURITY',        'major',    TRUE,  'User-controlled input is concatenated into a SQL string passed to execute() or similar.', CURRENT_TIMESTAMP),
    ('SECURITY_WEAK_CRYPTO',           'Weak Cryptography',  'SECURITY',        'major',    TRUE,  'Use of weak or broken cryptographic primitives (MD5, SHA-1, DES, RC4, Math.random, etc.).', CURRENT_TIMESTAMP),
    ('PERFORMANCE_N_PLUS_ONE',         'N+1 Query',          'PERFORMANCE',     'major',    TRUE,  'A loop performs a per-iteration database or remote call that should be batched.', CURRENT_TIMESTAMP),
    ('PERFORMANCE_QUADRATIC_LOOP',     'Quadratic Loop',     'PERFORMANCE',     'major',    TRUE,  'Nested iteration with O(n^2) or worse complexity.', CURRENT_TIMESTAMP),
    ('RELIABILITY_BROAD_EXCEPTION',    'Broad Exception',    'RELIABILITY',     'major',    TRUE,  'A bare except, catch-all Exception, or Java catch (Exception|Throwable) that swallows errors.', CURRENT_TIMESTAMP),
    ('RELIABILITY_MISSING_TIMEOUT',    'Missing Timeout',    'RELIABILITY',     'minor',    TRUE,  'External call (HTTP, DB, lock, queue) has no timeout.', CURRENT_TIMESTAMP),
    ('READABILITY_MAGIC_NUMBER',       'Magic Number',       'READABILITY',     'minor',    TRUE,  'Unexplained numeric literal in code.', CURRENT_TIMESTAMP),
    ('READABILITY_LONG_METHOD',        'Long Statement',     'READABILITY',     'minor',    TRUE,  'A single statement or line that exceeds reasonable length.', CURRENT_TIMESTAMP),
    ('MAINTAINABILITY_DUPLICATE_CODE', 'Duplicate Code',     'MAINTAINABILITY', 'minor',    TRUE,  'Identical or near-identical logic appears in multiple places.', CURRENT_TIMESTAMP),
    ('MAINTAINABILITY_COMMENTED_CODE', 'Commented-Out Code', 'MAINTAINABILITY', 'minor',    FALSE, 'Multi-line blocks of commented-out code.', CURRENT_TIMESTAMP),
    ('MAINTAINABILITY_PRINT_STATEMENT','Print Statement',    'MAINTAINABILITY', 'minor',    FALSE, 'A bare print() (Python) or console.log (JS/TS) remains in production code.', CURRENT_TIMESTAMP);

DELETE FROM anti_patterns WHERE id NOT IN (
    'SECURITY_HARDCODED_SECRET', 'SECURITY_SQL_INJECTION', 'SECURITY_WEAK_CRYPTO',
    'PERFORMANCE_N_PLUS_ONE', 'PERFORMANCE_QUADRATIC_LOOP',
    'RELIABILITY_BROAD_EXCEPTION', 'RELIABILITY_MISSING_TIMEOUT',
    'READABILITY_MAGIC_NUMBER', 'READABILITY_LONG_METHOD',
    'MAINTAINABILITY_DUPLICATE_CODE', 'MAINTAINABILITY_COMMENTED_CODE',
    'MAINTAINABILITY_PRINT_STATEMENT'
);

ALTER TABLE users DROP COLUMN IF EXISTS refresh_token;
ALTER TABLE users DROP COLUMN IF EXISTS api_key_hash;
ALTER TABLE users DROP COLUMN IF EXISTS api_key_prefix;
