package com.automatedcodereviewtool.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V19–V21 on real PostgreSQL. Migrates to V17 (the last PostgreSQL migration
 * before V19; V18 exists only in the H2 mirror) first and seeds a finding
 * dismissed under the old schema, so the V19 data migration (disposition_at
 * → triaged_at) and the constraints H2 cannot fully reproduce (btrim-based
 * reason check, regex CHECKs, FK ON DELETE SET NULL, index replacement)
 * are proven against the production dialect.
 */
@Tag("postgres")
@Testcontainers
class TriageAndSettingsMigrationPostgresTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("acrt_v19_v21")
            .withUsername("test")
            .withPassword("test");

    static JdbcTemplate jdbc;
    static final UUID USER_ID = UUID.randomUUID();
    static final UUID REPO_ID = UUID.randomUUID();
    static final UUID PR_ID = UUID.randomUUID();
    static final UUID LEGACY_FINDING_ID = UUID.randomUUID();

    @BeforeAll
    static void migrateAcrossV19() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        flyway(dataSource, "17").migrate();
        jdbc.update("INSERT INTO users (id, github_id, github_username, access_token) VALUES (?, 1, 'u', 'enc')", USER_ID);
        jdbc.update("INSERT INTO repositories (id, github_id, full_name, owner_id) VALUES (?, 1, 'u/app', ?)", REPO_ID, USER_ID);
        jdbc.update("INSERT INTO pull_requests (id, github_pr_number, repo_id, status) VALUES (?, 1, ?, 'reviewed')", PR_ID, REPO_ID);
        jdbc.update("""
                INSERT INTO findings (id, pr_id, file_path, anti_pattern, category, severity, confidence,
                                      status, disposition_at)
                VALUES (?, ?, 'a.py', 'SECURITY_WEAK_CRYPTO', 'SECURITY', 'major', 0.9,
                        'dismissed', TIMESTAMP '2026-01-02 03:04:05')
                """, LEGACY_FINDING_ID, PR_ID);

        flyway(dataSource, "latest").migrate();
    }

    private static Flyway flyway(DriverManagerDataSource dataSource, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static UUID insertFinding(String status, String reason) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO findings (id, pr_id, file_path, anti_pattern, category, severity, confidence,
                                      status, triage_reason)
                VALUES (?, ?, 'a.py', 'SECURITY_WEAK_CRYPTO', 'SECURITY', 'major', 0.9, ?, ?)
                """, id, PR_ID, status, reason);
        return id;
    }

    // --- V19 ---------------------------------------------------------------------

    @Test
    void v19_movesDispositionTimeToTriagedAt_andKeepsReasonlessLegacyDismissals() {
        Timestamp expected = jdbc.queryForObject(
                "SELECT CAST(TIMESTAMP '2026-01-02 03:04:05' AS timestamptz)", Timestamp.class);
        var row = jdbc.queryForMap("SELECT status, triaged_at, triage_reason FROM findings WHERE id = ?",
                LEGACY_FINDING_ID);
        assertThat(row.get("status")).isEqualTo("dismissed");
        assertThat(row.get("triaged_at")).isEqualTo(expected);
        assertThat(row.get("triage_reason")).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_name = 'findings' AND column_name = 'disposition_at'
                """, Integer.class)).isZero();
    }

    @Test
    void v19_statusCheckAcceptsFalsePositiveAndRejectsUnknownValues() {
        insertFinding("false_positive", "cache key, not a password hash");
        for (String status : List.of("open", "accepted", "dismissed", "fixed")) {
            insertFinding(status, null);
        }
        assertThatThrownBy(() -> insertFinding("wontfix", "x"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_finding_status");
    }

    @Test
    void v19_falsePositiveRequiresANonBlankReason() {
        for (String reason : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> insertFinding("false_positive", reason))
                    .as("reason=%s", reason)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_finding_false_positive_reason");
        }
        assertThatThrownBy(() -> insertFinding("dismissed", "x".repeat(501)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void v19_triagedByIsClearedWhenTheUserIsDeleted() {
        UUID triager = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, github_id, github_username, access_token) VALUES (?, 2, 't', 'enc')", triager);
        UUID finding = insertFinding("accepted", null);
        jdbc.update("UPDATE findings SET triaged_by = ?, triaged_at = NOW() WHERE id = ?", triager, finding);

        jdbc.update("DELETE FROM users WHERE id = ?", triager);

        var row = jdbc.queryForMap("SELECT triaged_by, triaged_at FROM findings WHERE id = ?", finding);
        assertThat(row.get("triaged_by")).isNull();
        assertThat(row.get("triaged_at")).isNotNull();
    }

    // --- V20 ---------------------------------------------------------------------

    @Test
    void v20_existingRepositoriesGetTheDocumentedDefaults() {
        var row = jdbc.queryForMap("""
                SELECT review_enabled_rules, review_min_severity, review_min_confidence,
                       review_post_comments, review_inline_comments, review_languages
                  FROM repositories WHERE id = ?
                """, REPO_ID);
        assertThat(row.get("review_enabled_rules")).isEqualTo("");
        assertThat(row.get("review_min_severity")).isEqualTo("minor");
        assertThat(((java.math.BigDecimal) row.get("review_min_confidence")).signum()).isZero();
        assertThat(row.get("review_post_comments")).isEqualTo(true);
        assertThat(row.get("review_inline_comments")).isEqualTo(false);
        assertThat(row.get("review_languages")).isEqualTo("python,javascript,typescript,java");
    }

    @Test
    void v20_constraintsRejectValuesOutsideTheContract() {
        for (String assignment : List.of(
                "review_min_severity = 'blocker'",
                "review_min_confidence = 1.001",
                "review_min_confidence = -0.001",
                "review_enabled_rules = 'SECURITY_WEAK_CRYPTO,'",
                "review_enabled_rules = 'security_weak_crypto'",
                "review_languages = ''",
                "review_languages = 'python,go'",
                "review_languages = 'python, java'")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE repositories SET " + assignment + " WHERE id = ?", REPO_ID))
                    .as(assignment)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        jdbc.update("""
                UPDATE repositories
                   SET review_enabled_rules = 'SECURITY_HARDCODED_SECRET,SECURITY_WEAK_CRYPTO',
                       review_min_severity = 'critical', review_min_confidence = 1,
                       review_languages = 'java'
                 WHERE id = ?
                """, REPO_ID);
        jdbc.update("""
                UPDATE repositories
                   SET review_enabled_rules = '', review_min_severity = 'minor', review_min_confidence = 0,
                       review_languages = 'python,javascript,typescript,java'
                 WHERE id = ?
                """, REPO_ID);
    }

    // --- V21 ---------------------------------------------------------------------

    @Test
    void v21_replacesTheRepoIdIndexWithTheHistoryIndex() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'pull_requests'", String.class);
        assertThat(indexes).contains("idx_prs_repo_created_at", "uq_pr_repo").doesNotContain("idx_prs_repo_id");
        String definition = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_prs_repo_created_at'", String.class);
        assertThat(definition).contains("(repo_id, created_at DESC)");
    }
}
