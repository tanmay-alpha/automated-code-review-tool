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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("postgres")
@Testcontainers
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OutboxClaimService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReliabilityPostgresTest {

    private static final AtomicLong GITHUB_ID = new AtomicLong(8_000_000L);

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("acrt_reliability_test")
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
    void frozenDatasetBlocksVersionDeleteAndEverySampleMutationPath() {
        RepoFixture fixture = insertRepositoryAndPullRequest();
        UUID sample = insertCodeSample(fixture, "a".repeat(64), "b".repeat(64), "src/A.java");
        UUID otherSample = insertCodeSample(fixture, "c".repeat(64), "d".repeat(64), "src/B.java");
        UUID annotation = UUID.randomUUID();
        UUID draftDataset = insertDataset("draft");
        UUID frozenDataset = insertDataset("frozen");

        jdbc.update("""
                INSERT INTO ml.annotations (
                    id, code_sample_id, anti_pattern_id, label_state, line_start,
                    line_end, source, confidence, trust_level, rationale
                ) VALUES (?, ?, 'UNKNOWN', 'positive', 1, 1, 'human', 1.0, 'human_single', 'ok')
                """, annotation, sample);
        insertDatasetItem(draftDataset, sample);
        insertDatasetItem(frozenDataset, sample);
        jdbc.update("UPDATE ml.dataset_versions SET status = 'frozen', frozen_at = NOW() WHERE id = ?",
                frozenDataset);

        assertBlocked(() -> jdbc.update(
                "UPDATE ml.dataset_versions SET manifest_sha256 = ? WHERE id = ?",
                "e".repeat(64), frozenDataset));
        assertBlocked(() -> jdbc.update("DELETE FROM ml.dataset_versions WHERE id = ?", frozenDataset));
        assertBlocked(() -> jdbc.update("UPDATE ml.code_samples SET raw_hunk = 'changed' WHERE id = ?", sample));
        assertBlocked(() -> jdbc.update("DELETE FROM ml.code_samples WHERE id = ?", sample));
        assertBlocked(() -> jdbc.update("UPDATE ml.annotations SET rationale = 'changed' WHERE id = ?", annotation));
        assertBlocked(() -> jdbc.update("UPDATE ml.annotations SET code_sample_id = ? WHERE id = ?",
                otherSample, annotation));
        assertBlocked(() -> jdbc.update(
                "UPDATE ml.dataset_items SET dataset_version_id = ? WHERE dataset_version_id = ? AND code_sample_id = ?",
                draftDataset, frozenDataset, sample));
        assertBlocked(() -> jdbc.update("""
                INSERT INTO ml.annotations (
                    id, code_sample_id, anti_pattern_id, label_state, line_start,
                    line_end, source, confidence, trust_level
                ) VALUES (?, ?, 'UNKNOWN', 'positive', 1, 1, 'human', 1.0, 'human_single')
                """, UUID.randomUUID(), sample));
    }

    @Test
    void outboxClaimsOnlyEligibleRowsAndNeverDoubleClaimsConcurrently() throws Exception {
        UUID future = insertOutbox("future", "pending", 0, 5,
                OffsetDateTime.now().plusHours(1), null);
        assertThat(outboxClaimService.claim("future-check", 10, OffsetDateTime.now().minusMinutes(5)))
                .isEmpty();
        assertThat(statusOf(future)).isEqualTo("pending");

        jdbc.update("DELETE FROM ml.ingestion_outbox");
        Set<UUID> inserted = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            inserted.add(insertOutbox("concurrent-" + i, "pending", 0, 5,
                    OffsetDateTime.now().minusSeconds(1), null));
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<List<IngestionOutbox>> first = executor.submit(() -> {
                start.await();
                return outboxClaimService.claim("worker-a", 10, OffsetDateTime.now().minusMinutes(5));
            });
            Future<List<IngestionOutbox>> second = executor.submit(() -> {
                start.await();
                return outboxClaimService.claim("worker-b", 10, OffsetDateTime.now().minusMinutes(5));
            });
            start.countDown();

            Set<UUID> firstIds = ids(first.get());
            Set<UUID> secondIds = ids(second.get());
            assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
            Set<UUID> claimed = new HashSet<>(firstIds);
            claimed.addAll(secondIds);
            assertThat(claimed).isEqualTo(inserted);
        } finally {
            executor.shutdownNow();
        }

        Integer processing = jdbc.queryForObject(
                "SELECT count(*) FROM ml.ingestion_outbox WHERE status = 'processing' AND attempt_count = 1",
                Integer.class);
        assertThat(processing).isEqualTo(20);
    }

    @Test
    void outboxRecoversStaleLeasesAndDeadLettersExhaustedRows() {
        UUID stale = insertOutbox("stale", "processing", 1, 3,
                OffsetDateTime.now().minusMinutes(10), OffsetDateTime.now().minusMinutes(10));
        UUID exhausted = insertOutbox("exhausted", "processing", 3, 3,
                OffsetDateTime.now().minusMinutes(10), OffsetDateTime.now().minusMinutes(10));

        List<IngestionOutbox> claimed = outboxClaimService.claim(
                "recovery-worker", 10, OffsetDateTime.now().minusMinutes(5));

        assertThat(ids(claimed)).containsExactly(stale);
        assertThat(statusOf(stale)).isEqualTo("processing");
        assertThat(statusOf(exhausted)).isEqualTo("dead_letter");
        assertThat(jdbc.queryForObject(
                "SELECT dead_lettered_at IS NOT NULL FROM ml.ingestion_outbox WHERE id = ?",
                Boolean.class, exhausted)).isTrue();
    }

    @Test
    void webhookIngressDeduplicatesLogicalReviewsAndPersistsRetrySchedule() {
        RepoFixture fixture = insertRepositoryAndPullRequest();
        String head = "1".repeat(40);

        assertThat(processedWebhookRepository.insertReceived(
                "delivery-1", fixture.repositoryId(), 42, head, "opened")).isEqualTo(1);
        assertThat(processedWebhookRepository.insertReceived(
                "delivery-1", fixture.repositoryId(), 42, head, "opened")).isZero();
        assertThat(processedWebhookRepository.insertReceived(
                "delivery-2", fixture.repositoryId(), 42, head, "synchronize")).isZero();
        assertThat(processedWebhookRepository.insertReceived(
                "delivery-3", fixture.repositoryId(), 42, "2".repeat(40), "synchronize")).isEqualTo(1);

        assertThat(processedWebhookRepository.claim("delivery-1")).isEqualTo(1);
        assertThat(processedWebhookRepository.claim("delivery-1")).isZero();
        assertThat(processedWebhookRepository.markFailed(
                "delivery-1", Instant.now().plusSeconds(60), "retryable failure")).isEqualTo(1);
        assertThat(processedWebhookRepository.claim("delivery-1")).isZero();

        jdbc.update("UPDATE processed_webhooks SET available_at = NOW() - INTERVAL '1 second' "
                + "WHERE delivery_id = 'delivery-1'");
        assertThat(processedWebhookRepository.claim("delivery-1")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT attempt_count FROM processed_webhooks WHERE delivery_id = 'delivery-1'",
                Integer.class)).isEqualTo(2);
    }

    private RepoFixture insertRepositoryAndPullRequest() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID pullRequestId = UUID.randomUUID();
        long githubId = GITHUB_ID.incrementAndGet();
        jdbc.update("""
                INSERT INTO users (id, github_id, github_username, access_token)
                VALUES (?, ?, ?, 'encrypted-test-token')
                """, userId, githubId, "user-" + githubId);
        jdbc.update("""
                INSERT INTO repositories (id, github_id, full_name, owner_id)
                VALUES (?, ?, ?, ?)
                """, repositoryId, githubId, "owner/repo-" + githubId, userId);
        jdbc.update("""
                INSERT INTO pull_requests (id, github_pr_number, repo_id, title, head_sha, status)
                VALUES (?, 42, ?, 'test', ?, 'processing')
                """, pullRequestId, repositoryId, "f".repeat(40));
        return new RepoFixture(repositoryId, pullRequestId);
    }

    private UUID insertCodeSample(RepoFixture fixture, String contentHash, String hunkHash, String path) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ml.code_samples (
                    id, repository_id, pull_request_id, commit_sha, file_path, language,
                    old_start, old_count, new_start, new_count, raw_hunk, added_code,
                    context_code, content_sha256, hunk_sha256, group_key, source_type,
                    redaction_version
                ) VALUES (?, ?, ?, ?, ?, 'java', 1, 1, 1, 1, '@@ -1 +1 @@',
                          'safe', 'safe', ?, ?, ?, 'pr_diff', 'v1')
                """, id, fixture.repositoryId(), fixture.pullRequestId(), "f".repeat(40), path,
                contentHash, hunkHash, fixture.repositoryId() + ":" + fixture.pullRequestId());
        return id;
    }

    private UUID insertDataset(String suffix) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ml.dataset_versions (
                    id, name, version, taxonomy_version, status, manifest_sha256
                ) VALUES (?, ?, '1', '1', 'draft', ?)
                """, id, "dataset-" + suffix + "-" + id, "0".repeat(64));
        return id;
    }

    private void insertDatasetItem(UUID dataset, UUID sample) {
        jdbc.update("""
                INSERT INTO ml.dataset_items (
                    dataset_version_id, code_sample_id, split, group_key, labels_snapshot
                ) VALUES (?, ?, 'train', 'group', CAST('[]' AS jsonb))
                """, dataset, sample);
    }

    private UUID insertOutbox(String key, String status, int attempts, int maxAttempts,
                              OffsetDateTime availableAt, OffsetDateTime lockedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ml.ingestion_outbox (
                    id, event_type, aggregate_type, aggregate_id, payload, status,
                    attempt_count, max_attempts, available_at, locked_at, locked_by,
                    deduplication_key, created_at, updated_at
                ) VALUES (?, 'DATASET_CAPTURE_REQUESTED', 'pull_request', ?, CAST('{}' AS jsonb),
                          ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """, id, UUID.randomUUID(), status, attempts, maxAttempts, availableAt,
                lockedAt, lockedAt == null ? null : "old-worker", key + "-" + id);
        return id;
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject(
                "SELECT status FROM ml.ingestion_outbox WHERE id = ?", String.class, id);
    }

    private static Set<UUID> ids(List<IngestionOutbox> rows) {
        Set<UUID> ids = new HashSet<>();
        rows.forEach(row -> ids.add(row.getId()));
        return ids;
    }

    private static void assertBlocked(Runnable mutation) {
        assertThatThrownBy(mutation::run).isInstanceOf(DataAccessException.class);
    }

    private record RepoFixture(UUID repositoryId, UUID pullRequestId) {
    }
}
