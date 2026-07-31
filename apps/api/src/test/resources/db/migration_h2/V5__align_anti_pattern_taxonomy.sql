-- ============================================
-- V5 (H2 mirror): align anti_pattern taxonomy
-- ============================================
-- H2 does not support `ALTER TABLE ... TYPE USING`,
-- but the original Postgres migration is already idempotent
-- data-only DML — we replicate the trimmed value set here.

UPDATE findings
SET anti_pattern = 'LONG_METHOD'
WHERE anti_pattern IN ('LONG_FUNCTION', 'TOO_LONG', 'LONG_FUNC');

MERGE INTO anti_patterns KEY(id) VALUES
    ('PERFORMANCE_N_PLUS_ONE',          'N+1 Query',                       'PERFORMANCE',     'major',    'Looping over results and issuing a query per iteration', TRUE, CURRENT_TIMESTAMP),
    ('PERFORMANCE_MISSING_INDEX',       'Missing Database Index',          'PERFORMANCE',     'major',    'Query scans a large table because no index exists', TRUE, CURRENT_TIMESTAMP),
    ('PERFORMANCE_FULL_TABLE_SCAN',     'Full Table Scan',                 'PERFORMANCE',     'major',    'Query plan performs a sequential scan on a large table', TRUE, CURRENT_TIMESTAMP),
    ('SECURITY_HARDCODED_SECRET',       'Hardcoded Secret',                'SECURITY',        'critical', 'API key, password, or token embedded in source', TRUE, CURRENT_TIMESTAMP),
    ('SECURITY_SQL_INJECTION',          'SQL Injection Risk',              'SECURITY',        'critical', 'String concatenation used to build a SQL query', TRUE, CURRENT_TIMESTAMP),
    ('SECURITY_XSS_VULNERABILITY',      'Cross-Site Scripting (XSS)',      'SECURITY',        'critical', 'Unescaped user input rendered into HTML', TRUE, CURRENT_TIMESTAMP),
    ('SECURITY_WEAK_CRYPTO',            'Weak Cryptography',               'SECURITY',        'major',    'Use of MD5, SHA-1, or other broken algorithm', TRUE, CURRENT_TIMESTAMP),
    ('SECURITY_INSECURE_DESERIAL',      'Insecure Deserialization',        'SECURITY',        'critical', 'Untrusted data passed to a deserializer', TRUE, CURRENT_TIMESTAMP),
    ('SECURITY_MISSING_AUTH',           'Missing Authorization Check',     'SECURITY',        'major',    'Endpoint reachable without authentication', TRUE, CURRENT_TIMESTAMP),
    ('SECURITY_SENSITIVE_LOGGING',      'Sensitive Data in Logs',          'SECURITY',        'major',    'PII or secrets written to log output', TRUE, CURRENT_TIMESTAMP),
    ('ARCHITECTURE_GOD_CLASS',          'God Class',                       'ARCHITECTURE',    'major',    'Single class with too many responsibilities', TRUE, CURRENT_TIMESTAMP),
    ('ARCHITECTURE_CIRCULAR_DEP',       'Circular Dependency',             'ARCHITECTURE',    'major',    'Mutual dependency between modules', TRUE, CURRENT_TIMESTAMP),
    ('ARCHITECTURE_DEEP_NESTING',       'Deep Nesting',                    'ARCHITECTURE',    'minor',    'Excessive nesting depth reduces readability', TRUE, CURRENT_TIMESTAMP),
    ('RELIABILITY_MISSING_ERROR_HANDLING', 'Missing Error Handling',       'RELIABILITY',     'major',    'No try/catch around external call', TRUE, CURRENT_TIMESTAMP),
    ('RELIABILITY_RESOURCE_LEAK',       'Resource Leak',                   'RELIABILITY',     'major',    'Stream, connection, or file not closed', TRUE, CURRENT_TIMESTAMP),
    ('RELIABILITY_RACE_CONDITION',      'Race Condition',                  'RELIABILITY',     'major',    'Concurrent access without synchronization', TRUE, CURRENT_TIMESTAMP),
    ('RELIABILITY_BROAD_EXCEPTION',     'Broad Exception Handler',         'RELIABILITY',     'major',    'Bare except or except Exception that swallows errors', TRUE, CURRENT_TIMESTAMP),
    ('READABILITY_DEAD_CODE',           'Dead Code',                       'READABILITY',     'minor',    'Unreachable or unused code block', TRUE, CURRENT_TIMESTAMP),
    ('READABILITY_LONG_METHOD',         'Long Method',                     'READABILITY',     'minor',    'Method exceeds reasonable length', TRUE, CURRENT_TIMESTAMP),
    ('READABILITY_MISLEADING_NAME',     'Misleading Variable Name',        'READABILITY',     'minor',    'Name does not match actual behavior', TRUE, CURRENT_TIMESTAMP),
    ('READABILITY_MAGIC_NUMBER',        'Magic Number',                    'READABILITY',     'minor',    'Unexplained numeric literal in business logic', TRUE, CURRENT_TIMESTAMP),
    ('MAINTAINABILITY_DUPLICATE_CODE',  'Duplicate Code',                  'MAINTAINABILITY', 'minor',    'Copy-pasted logic instead of a shared function', TRUE, CURRENT_TIMESTAMP),
    ('MAINTAINABILITY_TIGHT_COUPLING',  'Tight Coupling',                  'MAINTAINABILITY', 'minor',    'Module depends on another internal detail', TRUE, CURRENT_TIMESTAMP),
    ('MAINTAINABILITY_MISSING_TESTS',   'Missing Tests',                   'MAINTAINABILITY', 'minor',    'No unit or integration tests for this logic', TRUE, CURRENT_TIMESTAMP),
    ('MAINTAINABILITY_PRINT_STATEMENT', 'Print Statement',                 'MAINTAINABILITY', 'minor',    'print(...) left in production code', FALSE, CURRENT_TIMESTAMP),
    ('READABILITY_COMMENTED_OUT_CODE',  'Commented-Out Code',              'READABILITY',     'minor',    'Large blocks of commented-out code', FALSE, CURRENT_TIMESTAMP);
