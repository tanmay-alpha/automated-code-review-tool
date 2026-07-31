package com.automatedcodereviewtool.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for V6 Flyway migration (ML dataset foundation).
 *
 * <p>Verifies that the {@code ml} schema is created and that all four
 * tables ({@code code_samples}, {@code annotations},
 * {@code dataset_versions}, {@code dataset_items}) exist with the
 * columns and uniqueness constraints declared in V6__ml_dataset_foundation.sql.</p>
 *
 * <p>Uses Testcontainers PostgreSQL — no mocks for migration behaviour.</p>
 */
@Testcontainers
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V6MlDatasetMigrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("acrt_test_v6")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.flyway.locations", () -> "classpath:db/migration");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("app.jwt.secret", () -> "test-secret-key-32-bytes-long-for-hs256-min");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void mlSchemaExists() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'ml'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void codeSamplesTableHasRequiredColumns() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'ml' AND table_name = 'code_samples' "
                        + "AND column_name IN ('id','repository_id','pull_request_id','commit_sha',"
                        + "'file_path','language','old_start','old_count','new_start','new_count',"
                        + "'raw_hunk','added_code','context_code','content_sha256','group_key',"
                        + "'source_type','redaction_version','created_at')",
                Integer.class);
        assertThat(count).isEqualTo(18);
    }

    @Test
    void uniquenessConstraintOnCodeSamplesExists() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'ml' AND table_name = 'code_samples' "
                        + "AND constraint_type = 'UNIQUE' "
                        + "AND constraint_name = 'uq_code_samples_pr_commit_file_start_hash'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void annotationsTableExistsWithForeignKey() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'ml' AND table_name = 'annotations' "
                        + "AND constraint_type = 'FOREIGN KEY'",
                Integer.class);
        // At least code_sample_id -> code_samples(id) and anti_pattern_id -> anti_patterns(id)
        assertThat(count).isGreaterThanOrEqualTo(2);
    }

    @Test
    void datasetItemsHasCompositePrimaryKey() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'ml' AND table_name = 'dataset_items' "
                        + "AND constraint_type = 'PRIMARY KEY'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void datasetVersionsHasManifestHash() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'ml' AND table_name = 'dataset_versions' "
                        + "AND column_name = 'manifest_sha256'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
