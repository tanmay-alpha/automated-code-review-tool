package com.automatedcodereviewtool.reliability;

import com.automatedcodereviewtool.entity.IngestionOutbox;
import com.automatedcodereviewtool.repository.ProcessedWebhookRepository;
import com.automatedcodereviewtool.service.OutboxClaimService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lease guards for both durable queues on real Postgres: a stale webhook
 * worker can't overwrite a newer claim, and exhausted rows are dead-lettered
 * only once their processing lease has expired.
 */
@Tag("postgres")
@Testcontainers
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OutboxClaimService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LeaseGuardPostgresTest {

    private static final AtomicLong GITHUB_ID = new AtomicLong(9_000_000L);

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("acrt_lease_guard_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxClaimService outboxClaimService;

    @Autowired
    private ProcessedWebhookRepository processedWebhookRepository;

    @BeforeEach
    void clearQueues() {
        jdbc.update("DELETE FROM ml.ingestion_outbox");
        jdbc.update("DELETE FROM processed_webhooks");
    }

    @Test
    void staleWebhookWorkerCannotOverwriteNewerClaim() {
        UUID repoId = insertRepository();
        assertThat(processedWebhookRepository.insertReceived(
                "fenced", repoId, 42, "a".repeat(40), "opened")).isEqualTo(1);

        // Worker A claims attempt 1, then its lease expires and is recovered.
        assertThat(processedWebhookRepository.claim("fenced", 0)).isEqualTo(1);
        jdbc.update("UPDATE processed_webhooks SET processing_started_at = NOW() - INTERVAL '20 minutes' "
                + "WHERE delivery_id = 'fenced'");
        assertThat(processedWebhookRepository.recoverStale(Instant.now().minusSeconds(600))).isEqualTo(1);

        // Worker B claims attempt 2.
        assertThat(processedWebhookRepository.claim("fenced", 1)).isEqualTo(1);

        // Worker A finishes late: neither completion nor failure may land.
        assertThat(processedWebhookRepository.markTerminal("fenced", 1, "completed", null)).isZero();
        assertThat(processedWebhookRepository.markFailed(
                "fenced", 1, Instant.now().plusSeconds(60), "late failure")).isZero();
        assertThat(webhookStatus("fenced")).isEqualTo("processing");
        assertThat(jdbc.queryForObject(
                "SELECT attempt_count FROM processed_webhooks WHERE delivery_id = 'fenced'",
                Integer.class)).isEqualTo(2);

        // The current owner still completes normally.
        assertThat(processedWebhookRepository.markTerminal("fenced", 2, "completed", null)).isEqualTo(1);
        assertThat(webhookStatus("fenced")).isEqualTo("completed");
    }

    @Test
    void webhookDeadLetterWaitsForExpiredLease() {
        UUID repoId = insertRepository();
        insertExhaustedProcessingWebhook("fresh", repoId, "b".repeat(40), Instant.now());
        insertExhaustedProcessingWebhook("stale", repoId, "c".repeat(40),
                Instant.now().minusSeconds(1200));

        assertThat(processedWebhookRepository.deadLetterExhausted(Instant.now().minusSeconds(600)))
                .isEqualTo(1);

        assertThat(webhookStatus("fresh")).isEqualTo("processing");
        assertThat(webhookStatus("stale")).isEqualTo("dead_letter");
        // The final attempt that is still running can complete.
        assertThat(processedWebhookRepository.markTerminal("fresh", 5, "completed", null)).isEqualTo(1);
    }

    @Test
    void outboxDeadLetterWaitsForExpiredLease() {
        UUID fresh = insertExhaustedProcessingOutbox("fresh", OffsetDateTime.now());
        UUID stale = insertExhaustedProcessingOutbox("stale", OffsetDateTime.now().minusMinutes(20));

        assertThat(outboxClaimService.claim("guard-worker", 10, OffsetDateTime.now().minusMinutes(10)))
                .isEmpty();

        assertThat(outboxStatus(fresh)).isEqualTo("processing");
        assertThat(jdbc.queryForObject(
                "SELECT locked_by FROM ml.ingestion_outbox WHERE id = ?", String.class, fresh))
                .isEqualTo("old-worker");
        assertThat(outboxStatus(stale)).isEqualTo("dead_letter");
        // The lease holder of the fresh row can still record its outcome.
        assertThat(outboxClaimService.fail(fresh, "old-worker", 3, "final failure", OffsetDateTime.now()))
                .isTrue();
        assertThat(outboxStatus(fresh)).isEqualTo("dead_letter");
    }

    @Test
    void staleOutboxAttemptCannotCompleteAfterSameWorkerReclaims() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ml.ingestion_outbox (
                    id, event_type, aggregate_type, aggregate_id, payload, status,
                    attempt_count, max_attempts, available_at, deduplication_key, created_at, updated_at
                ) VALUES (?, 'DATASET_CAPTURE_REQUESTED', 'pull_request', ?, CAST('{}' AS jsonb),
                          'pending', 0, 5, NOW() - INTERVAL '1 second', ?, NOW(), NOW())
                """, id, UUID.randomUUID(), "self-reclaim-" + id);

        // Attempt 1 is claimed, then its lease expires while it is still running.
        List<IngestionOutbox> first = outboxClaimService.claim("same-worker", 10, OffsetDateTime.now().minusMinutes(5));
        assertThat(first).extracting(IngestionOutbox::getAttemptCount).containsExactly(1);
        jdbc.update("UPDATE ml.ingestion_outbox SET locked_at = NOW() - INTERVAL '10 minutes' WHERE id = ?", id);

        // The same process (same worker id) recovers and re-claims it as attempt 2.
        List<IngestionOutbox> second = outboxClaimService.claim("same-worker", 10, OffsetDateTime.now().minusMinutes(5));
        assertThat(second).extracting(IngestionOutbox::getAttemptCount).containsExactly(2);

        // The stale attempt finishes late: neither completion nor failure lands.
        assertThat(outboxClaimService.complete(id, "same-worker", 1)).isFalse();
        assertThat(outboxClaimService.fail(id, "same-worker", 1, "late", OffsetDateTime.now())).isFalse();
        assertThat(outboxStatus(id)).isEqualTo("processing");

        assertThat(outboxClaimService.complete(id, "same-worker", 2)).isTrue();
        assertThat(outboxStatus(id)).isEqualTo("completed");
    }

    private UUID insertRepository() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        long githubId = GITHUB_ID.incrementAndGet();
        jdbc.update("""
                INSERT INTO users (id, github_id, github_username, access_token)
                VALUES (?, ?, ?, 'encrypted-test-token')
                """, userId, githubId, "user-" + githubId);
        jdbc.update("""
                INSERT INTO repositories (id, github_id, full_name, owner_id)
                VALUES (?, ?, ?, ?)
                """, repositoryId, githubId, "owner/repo-" + githubId, userId);
        return repositoryId;
    }

    private void insertExhaustedProcessingWebhook(String deliveryId, UUID repoId, String headSha,
                                                  Instant startedAt) {
        assertThat(processedWebhookRepository.insertReceived(
                deliveryId, repoId, 42, headSha, "opened")).isEqualTo(1);
        jdbc.update("""
                UPDATE processed_webhooks
                   SET status = 'processing', attempt_count = max_attempts,
                       processing_started_at = ?
                 WHERE delivery_id = ?
                """, java.sql.Timestamp.from(startedAt), deliveryId);
    }

    private UUID insertExhaustedProcessingOutbox(String key, OffsetDateTime lockedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ml.ingestion_outbox (
                    id, event_type, aggregate_type, aggregate_id, payload, status,
                    attempt_count, max_attempts, available_at, locked_at, locked_by,
                    deduplication_key, created_at, updated_at
                ) VALUES (?, 'DATASET_CAPTURE_REQUESTED', 'pull_request', ?, CAST('{}' AS jsonb),
                          'processing', 3, 3, ?, ?, 'old-worker', ?, NOW(), NOW())
                """, id, UUID.randomUUID(), lockedAt, lockedAt, key + "-" + id);
        return id;
    }

    private String webhookStatus(String deliveryId) {
        return jdbc.queryForObject(
                "SELECT status FROM processed_webhooks WHERE delivery_id = ?", String.class, deliveryId);
    }

    private String outboxStatus(UUID id) {
        return jdbc.queryForObject(
                "SELECT status FROM ml.ingestion_outbox WHERE id = ?", String.class, id);
    }
}
