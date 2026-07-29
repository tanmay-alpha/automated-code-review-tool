-- ============================================
-- V5: Align anti-pattern taxonomy with canonical YAML
-- ============================================
-- Canonical taxonomy lives in /taxonomy/anti_patterns.yaml at the
-- repository root and is loaded at runtime by both the Python ML
-- worker and the Spring Boot application (TaxonomyService). This
-- migration introduces persistent storage for that taxonomy plus a
-- single version string the API can expose alongside findings.
--
-- Goals of this migration:
--   1. Add taxonomy_version storage (singleton row in app_settings).
--   2. Extend anti_patterns with trainable + description columns and
--      keep V4 rows but add canonical alias mappings.
--   3. Re-point known alias IDs in existing findings to their
--      canonical form so reporting is consistent.
--   4. Preserve historical unknown IDs without breaking FKs.
--   5. Insert any canonical IDs that V4 didn't pre-populate.
--   6. Remove deprecated lookup rows ONLY after alias migration
--      leaves no findings referencing them.
--
-- V4 is intentionally untouched. Anyone who already runs the V5
-- stack against an existing database with findings rows referencing
-- obsolete IDs will see those rows transparently remapped.
-- ============================================

-- ============================================
-- 1. Singleton application settings
-- ============================================
-- Stores arbitrary key/value configuration that must survive across
-- deploys without being stuffed into Flyway placeholders.
CREATE TABLE IF NOT EXISTS app_settings (
    key          VARCHAR(64)  PRIMARY KEY,
    value        TEXT         NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

INSERT INTO app_settings (key, value) VALUES
    ('taxonomy.version', '1.0.0')
ON CONFLICT (key) DO NOTHING;

-- ============================================
-- 2. Extend anti_patterns with metadata
-- ============================================
-- trainable=true means the model head is allowed to predict it.
-- Fallback rules may also reference non-trainable patterns.
ALTER TABLE anti_patterns
    ADD COLUMN IF NOT EXISTS trainable     BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS description   TEXT,
    ADD COLUMN IF NOT EXISTS updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- ============================================
-- 3. Canonical taxonomy rows
-- ============================================
-- IDs below mirror taxonomy/anti_patterns.yaml exactly. They are
-- idempotent — already-present rows keep their category/severity
-- from V4 so dashboard lookups don't churn.
INSERT INTO anti_patterns (id, display_name, category, default_severity, trainable, description) VALUES
    ('PERFORMANCE_N_PLUS_ONE',          'N+1 Query',                       'PERFORMANCE',     'major',    TRUE, 'Looping over results and issuing a query per iteration'),
    ('PERFORMANCE_MISSING_INDEX',       'Missing Database Index',          'PERFORMANCE',     'major',    TRUE, 'Query scans a large table because no index exists'),
    ('PERFORMANCE_FULL_TABLE_SCAN',     'Full Table Scan',                 'PERFORMANCE',     'major',    TRUE, 'Query plan performs a sequential scan on a large table'),
    ('SECURITY_HARDCODED_SECRET',       'Hardcoded Secret',                'SECURITY',        'critical', TRUE, 'API key, password, or token embedded in source'),
    ('SECURITY_SQL_INJECTION',          'SQL Injection Risk',              'SECURITY',        'critical', TRUE, 'String concatenation used to build a SQL query'),
    ('SECURITY_XSS_VULNERABILITY',      'Cross-Site Scripting (XSS)',      'SECURITY',        'critical', TRUE, 'Unescaped user input rendered into HTML'),
    ('SECURITY_WEAK_CRYPTO',            'Weak Cryptography',               'SECURITY',        'major',    TRUE, 'Use of MD5, SHA-1, or other broken algorithm'),
    ('SECURITY_INSECURE_DESERIAL',      'Insecure Deserialization',        'SECURITY',        'critical', TRUE, 'Untrusted data passed to a deserializer'),
    ('SECURITY_MISSING_AUTH',           'Missing Authorization Check',     'SECURITY',        'major',    TRUE, 'Endpoint reachable without authentication'),
    ('SECURITY_SENSITIVE_LOGGING',      'Sensitive Data in Logs',          'SECURITY',        'major',    TRUE, 'PII or secrets written to log output'),
    ('ARCHITECTURE_GOD_CLASS',          'God Class',                       'ARCHITECTURE',    'major',    TRUE, 'Single class with too many responsibilities'),
    ('ARCHITECTURE_CIRCULAR_DEP',       'Circular Dependency',             'ARCHITECTURE',    'major',    TRUE, 'Mutual dependency between modules'),
    ('ARCHITECTURE_DEEP_NESTING',       'Deep Nesting',                    'ARCHITECTURE',    'minor',    TRUE, 'Excessive nesting depth reduces readability'),
    ('RELIABILITY_MISSING_ERROR_HANDLING', 'Missing Error Handling',       'RELIABILITY',     'major',    TRUE, 'No try/catch around external call'),
    ('RELIABILITY_RESOURCE_LEAK',       'Resource Leak',                   'RELIABILITY',     'major',    TRUE, 'Stream, connection, or file not closed'),
    ('RELIABILITY_RACE_CONDITION',      'Race Condition',                  'RELIABILITY',     'major',    TRUE, 'Concurrent access without synchronization'),
    ('RELIABILITY_BROAD_EXCEPTION',     'Broad Exception Handler',         'RELIABILITY',     'major',    TRUE, 'Bare except or except Exception that swallows errors'),
    ('READABILITY_DEAD_CODE',           'Dead Code',                       'READABILITY',     'minor',    TRUE, 'Unreachable or unused code block'),
    ('READABILITY_LONG_METHOD',         'Long Method',                     'READABILITY',     'minor',    TRUE, 'Method exceeds reasonable length'),
    ('READABILITY_MISLEADING_NAME',     'Misleading Variable Name',        'READABILITY',     'minor',    TRUE, 'Name does not match actual behavior'),
    ('READABILITY_MAGIC_NUMBER',        'Magic Number',                    'READABILITY',     'minor',    TRUE, 'Unexplained numeric literal in business logic'),
    ('MAINTAINABILITY_DUPLICATE_CODE',  'Duplicate Code',                  'MAINTAINABILITY', 'minor',    TRUE, 'Copy-pasted logic instead of a shared function'),
    ('MAINTAINABILITY_TIGHT_COUPLING',  'Tight Coupling',                  'MAINTAINABILITY', 'minor',    TRUE, 'Module depends on another internal detail'),
    ('MAINTAINABILITY_MISSING_TESTS',   'Missing Tests',                   'MAINTAINABILITY', 'minor',    TRUE, 'No unit or integration tests for this logic'),
    -- Non-trainable fallback rules (operational, not predicted by model)
    ('MAINTAINABILITY_PRINT_STATEMENT', 'Print Statement',                 'MAINTAINABILITY', 'minor',    FALSE, 'print(...) left in production code'),
    ('READABILITY_COMMENTED_OUT_CODE',  'Commented-Out Code',              'READABILITY',     'minor',    FALSE, 'Large blocks of commented-out code')
ON CONFLICT (id) DO NOTHING;

-- Re-stamp descriptions on V4 rows that didn't have one. WHERE clause
-- skips the rows we just inserted in this migration.
UPDATE anti_patterns SET description = 'Looping over results and issuing a query per iteration'
 WHERE id = 'PERFORMANCE_N_PLUS_1' AND description IS NULL;

-- ============================================
-- 4. Alias mapping for known legacy IDs
-- ============================================
-- This is the only place we guess at remapping. Anything ambiguous
-- is left in place and surfaced via the canonical YAML so it can be
-- reconciled manually. Map is intentionally tiny and well-known:
--   PERFORMANCE_N_PLUS_1    -> PERFORMANCE_N_PLUS_ONE
--   RELIABILITY_MAGIC_NUMBER -> READABILITY_MAGIC_NUMBER
--   RELY_BARE_EXCEPT        -> RELIABILITY_BROAD_EXCEPTION
--   READ_MAGIC_NUMBER       -> READABILITY_MAGIC_NUMBER
--
-- Step 1: ensure canonical rows exist (idempotent — done above).
-- Step 2: rewrite findings.anti_pattern.
-- Step 3: only then remove obsolete lookup rows.

UPDATE findings f
   SET anti_pattern = 'PERFORMANCE_N_PLUS_ONE'
 WHERE f.anti_pattern = 'PERFORMANCE_N_PLUS_1';

UPDATE findings f
   SET anti_pattern = 'READABILITY_MAGIC_NUMBER',
       category     = 'READABILITY'
 WHERE f.anti_pattern = 'RELIABILITY_MAGIC_NUMBER';

UPDATE findings f
   SET anti_pattern = 'RELIABILITY_BROAD_EXCEPTION'
 WHERE f.anti_pattern = 'RELY_BARE_EXCEPT';

UPDATE findings f
   SET anti_pattern = 'READABILITY_MAGIC_NUMBER'
 WHERE f.anti_pattern = 'READ_MAGIC_NUMBER';

-- ============================================
-- 5. Remove deprecated lookup rows
-- ============================================
-- Safe because the alias UPDATE statements above moved every
-- existing findings row off these IDs first. ON DELETE on findings
-- isn't cascaded here — those rows already point at canonical IDs.
DELETE FROM anti_patterns WHERE id IN (
    'PERFORMANCE_N_PLUS_1',
    'RELIABILITY_MAGIC_NUMBER',
    'RELY_BARE_EXCEPT',
    'READ_MAGIC_NUMBER'
);

-- ============================================
-- 6. CHECK constraints tightened
-- ============================================
-- Reinforce category allowlist in case V4 was applied out of order.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_anti_pattern_category'
    ) THEN
        ALTER TABLE anti_patterns
          ADD CONSTRAINT chk_anti_pattern_category
          CHECK (category IN (
              'SECURITY', 'PERFORMANCE', 'ARCHITECTURE',
              'RELIABILITY', 'READABILITY', 'MAINTAINABILITY'
          ));
    END IF;
END$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_anti_pattern_severity'
    ) THEN
        ALTER TABLE anti_patterns
          ADD CONSTRAINT chk_anti_pattern_severity
          CHECK (default_severity IN ('critical', 'major', 'minor'));
    END IF;
END$$;