package com.automatedcodereviewtool;

import com.automatedcodereviewtool.entity.Annotation;
import com.automatedcodereviewtool.entity.CodeSample;
import com.automatedcodereviewtool.entity.Finding;
import com.automatedcodereviewtool.entity.IngestionOutbox;
import com.automatedcodereviewtool.entity.PredictionEvent;
import com.automatedcodereviewtool.entity.PullRequestEntity;
import com.automatedcodereviewtool.entity.Repository;
import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.repository.AnnotationRepository;
import com.automatedcodereviewtool.repository.IngestionOutboxRepository;
import com.automatedcodereviewtool.repository.PredictionEventRepository;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for Phase 1B: annotation idempotency, reviewer
 * conflicts, outbox, rejected predictions, freeze protection.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class Phase1BIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("acrt_phase1b_test")
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
    private JdbcTemplate jdbc;

    @Autowired
    private AnnotationRepository annotationRepository;

    @Autowired
    private IngestionOutboxRepository outboxRepository;

    @Autowired
    private PredictionEventRepository predictionEventRepository;

    // ================================================================
    // Annotation idempotency
    // ================================================================

    @Test
    void annotationIdempotencyKeyIsUnique() {
        // Verify the database-level unique constraint exists.
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'ml' AND table_name = 'annotations' "
                        + "AND constraint_type = 'UNIQUE' "
                        + "AND constraint_name = 'uq_annotations_idempotency_key'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void feedbackActionAllowedValues() {
        // Verify the CHECK constraint for feedback_action.
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.check_constraints "
                        + "WHERE constraint_schema = 'ml' "
                        + "AND constraint_name = 'chk_annotation_feedback_action'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void resolutionStateAllowedValues() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.check_constraints "
                        + "WHERE constraint_schema = 'ml' "
                        + "AND constraint_name = 'chk_annotation_resolution_state'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    // ================================================================
    // Outbox table
    // ================================================================

    @Test
    void outboxTableExists() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'ml' AND table_name = 'ingestion_outbox'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void outboxStatusAllowedValues() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.check_constraints "
                        + "WHERE constraint_schema = 'ml' "
                        + "AND constraint_name = 'chk_outbox_status'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    // ================================================================
    // Prediction events table
    // ================================================================

    @Test
    void predictionEventsTableExists() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'ml' AND table_name = 'prediction_events'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void predictionStatusAllowedValues() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.check_constraints "
                        + "WHERE constraint_schema = 'ml' "
                        + "AND constraint_name = 'chk_prediction_status'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    // ================================================================
    // Sample reviews table
    // ================================================================

    @Test
    void sampleReviewsTableExists() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'ml' AND table_name = 'sample_reviews'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void sampleReviewStatusAllowedValues() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.check_constraints "
                        + "WHERE constraint_schema = 'ml' "
                        + "AND constraint_name = 'chk_sample_review_status'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    // ================================================================
    // Data-use fields
    // ================================================================

    @Test
    void repositoryHasDataUseStatusColumn() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'repositories' "
                        + "AND column_name = 'data_use_status'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void repositoryDataUseStatusConstraint() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.check_constraints "
                        + "WHERE constraint_schema = 'public' "
                        + "AND constraint_name = 'chk_repo_data_use_status'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void codeSampleHasDataUseStatusColumn() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'ml' AND table_name = 'code_samples' "
                        + "AND column_name = 'data_use_status'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    // ================================================================
    // Freeze protection
    // ================================================================

    @Test
    void frozenDatasetTriggerExists() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.triggers "
                        + "WHERE trigger_schema = 'ml' "
                        + "AND trigger_name = 'trg_dataset_items_immutable'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void annotationsFrozenTriggerExists() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.triggers "
                        + "WHERE trigger_schema = 'ml' "
                        + "AND trigger_name = 'trg_annotations_immutable'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
