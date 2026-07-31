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
 * Integration tests for V7 Flyway migration (link findings to code samples).
 *
 * <p>Verifies that the {@code findings} table gains a {@code code_sample_id}
 * foreign key and that {@code code_samples} gains a {@code hunk_hash}
 * column with its unique constraint.</p>
 *
 * <p>Uses Testcontainers PostgreSQL — no mocks for migration behaviour.</p>
 */
@Testcontainers
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V7MlCodeSamplesMigrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("acrt_test_v7")
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
    void findingsTableHasCodeSampleIdColumn() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'findings' "
                        + "AND column_name = 'code_sample_id'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void findingsCodeSampleIdHasForeignKey() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints tc "
                        + "JOIN information_schema.key_column_usage kcu "
                        + "  ON tc.constraint_name = kcu.constraint_name "
                        + "WHERE tc.table_schema = 'public' "
                        + "  AND tc.table_name = 'findings' "
                        + "  AND tc.constraint_type = 'FOREIGN KEY' "
                        + "  AND kcu.column_name = 'code_sample_id'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void codeSamplesTableHasHunkHashColumn() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'ml' AND table_name = 'code_samples' "
                        + "AND column_name = 'hunk_hash'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void codeSamplesHunkHashHasUniqueConstraint() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'ml' AND table_name = 'code_samples' "
                        + "AND constraint_type = 'UNIQUE' "
                        + "AND constraint_name = 'uq_code_samples_hunk_hash'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
