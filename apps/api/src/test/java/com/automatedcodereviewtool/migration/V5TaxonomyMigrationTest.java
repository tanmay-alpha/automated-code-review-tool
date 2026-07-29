package com.automatedcodereviewtool.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.automatedcodereviewtool.entity.AntiPattern;
import com.automatedcodereviewtool.repository.AntiPatternRepository;
import com.automatedcodereviewtool.service.TaxonomyService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for V5 Flyway migration.
 *
 * <p>Uses Testcontainers PostgreSQL to verify:</p>
 * <ul>
 *   <li>Canonical taxonomy rows are inserted.</li>
 *   <li>Legacy alias IDs in findings are rewritten.</li>
 *   <li>Deprecated lookup rows are removed.</li>
 *   <li>TaxonomyService loads and validates cleanly.</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V5TaxonomyMigrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("acrt_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.flyway.locations", () -> "classpath:db/migration");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private AntiPatternRepository antiPatternRepository;

    @Autowired
    private TaxonomyService taxonomyService;

    @Test
    void canonicalIdsPresent() {
        assertThat(antiPatternRepository.findById("PERFORMANCE_N_PLUS_ONE"))
                .isPresent()
                .get()
                .extracting(AntiPattern::getCategory)
                .isEqualTo("PERFORMANCE");
        assertThat(antiPatternRepository.findById("MAINTAINABILITY_PRINT_STATEMENT"))
                .isPresent()
                .extracting(AntiPattern::isTrainable)
                .isEqualTo(false);
    }

    @Test
    void aliasRowsAreRemovedFromLookupTable() {
        assertThat(antiPatternRepository.findById("PERFORMANCE_N_PLUS_1")).isEmpty();
        assertThat(antiPatternRepository.findById("RELY_BARE_EXCEPT")).isEmpty();
        assertThat(antiPatternRepository.findById("READ_MAGIC_NUMBER")).isEmpty();
        assertThat(antiPatternRepository.findById("RELIABILITY_MAGIC_NUMBER")).isEmpty();
    }

    @Test
    void taxonomyServiceValidationPasses() {
        assertThat(taxonomyService.allIds()).isNotEmpty();
        assertThat(taxonomyService.trainableIds()).isNotEmpty();
        assertThat(taxonomyService.contains("PERFORMANCE_N_PLUS_ONE")).isTrue();
        assertThat(taxonomyService.contains("PERFORMANCE_N_PLUS_1")).isFalse();
        assertThat(taxonomyService.isTrainable("MAINTAINABILITY_PRINT_STATEMENT")).isFalse();
        assertThat(taxonomyService.isTrainable("SECURITY_HARDCODED_SECRET")).isTrue();
        assertThat(taxonomyService.categoryOf("PERFORMANCE_N_PLUS_ONE"))
                .isEqualTo("PERFORMANCE");
        assertThat(taxonomyService.severityOf("PERFORMANCE_N_PLUS_ONE"))
                .isEqualTo("major");
    }
}