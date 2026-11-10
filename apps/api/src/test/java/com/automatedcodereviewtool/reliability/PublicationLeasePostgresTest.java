package com.automatedcodereviewtool.reliability;

import com.automatedcodereviewtool.repository.ProcessedWebhookRepository;
import com.automatedcodereviewtool.repository.ReviewPublicationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Publication leases and the review-transaction fence under real
 * concurrency on Postgres (H2 cannot reproduce row-lock races).
 */
@Tag("postgres")
@Testcontainers
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PublicationLeasePostgresTest {

    private static final AtomicLong GITHUB_ID = new AtomicLong(7_000_000L);

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("acrt_publication_test")
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

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReviewPublicationRepository publications;
    @Autowired private ProcessedWebhookRepository deliveries;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM review_publications");
        jdbc.update("DELETE FROM processed_webhooks");
    }

    @Test
    void concurrentPublishersClaimAPublicationOnce() throws Exception {
        UUID prId = insertPullRequest();
        UUID publicationId = insertPublication(prId, "a".repeat(40));

        List<Integer> results = race(8, () -> publications.claim(publicationId, 0, Instant.now().minusSeconds(300)));

        assertThat(results).containsOnly(0, 1);
        assertThat(results.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
    }

    @Test
    void onlyOnePublicationPerPullRequestHoldsALease() throws Exception {
        UUID prId = insertPullRequest();
        UUID first = insertPublication(prId, "a".repeat(40));
        UUID second = insertPublication(prId, "b".repeat(40));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // Same protocol as ReviewPublisher.claim: lock the PR row, then CAS.
        List<Integer> results = race(2, new Callable<>() {
            private final AtomicLong turn = new AtomicLong();

            @Override
            public Integer call() {
                UUID id = turn.getAndIncrement() == 0 ? first : second;
                return tx.execute(status -> {
                    jdbc.queryForObject("SELECT id FROM pull_requests WHERE id = ? FOR UPDATE", UUID.class, prId);
                    return publications.claim(id, 0, Instant.now().minusSeconds(300));
                });
            }
        });

        assertThat(results.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
    }

    @Test
    void reviewFenceBlocksAStaleAttemptWhileTheNewOwnerHoldsTheLease() throws Exception {
        UUID prId = insertPullRequest();
        UUID repoId = jdbc.queryForObject("SELECT repo_id FROM pull_requests WHERE id = ?", UUID.class, prId);
        assertThat(deliveries.insertReceived("fence", repoId, 42, "c".repeat(40), "opened")).isEqualTo(1);
        assertThat(deliveries.claim("fence", 0)).isEqualTo(1);
        jdbc.update("UPDATE processed_webhooks SET processing_started_at = NOW() - INTERVAL '20 minutes' "
                + "WHERE delivery_id = 'fence'");
        assertThat(deliveries.recoverStale(Instant.now().minusSeconds(600))).isEqualTo(1);
        assertThat(deliveries.claim("fence", 1)).isEqualTo(1);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Integer owner = tx.execute(status -> deliveries.lockProcessingAttempt("fence").orElse(null));

        // Attempt 1's review transaction sees attempt 2 and must discard its result.
        assertThat(owner).isEqualTo(2);
    }

    private <T> List<T> race(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private UUID insertPullRequest() {
        UUID userId = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();
        UUID prId = UUID.randomUUID();
        long githubId = GITHUB_ID.incrementAndGet();
        jdbc.update("INSERT INTO users (id, github_id, github_username, access_token) VALUES (?, ?, ?, 'x')",
                userId, githubId, "user-" + githubId);
        jdbc.update("INSERT INTO repositories (id, github_id, full_name, owner_id) VALUES (?, ?, ?, ?)",
                repoId, githubId, "owner/repo-" + githubId, userId);
        jdbc.update("INSERT INTO pull_requests (id, github_pr_number, repo_id, head_sha, status) "
                + "VALUES (?, 42, ?, ?, 'reviewed')", prId, repoId, "a".repeat(40));
        return prId;
    }

    private UUID insertPublication(UUID prId, String headSha) {
        UUID repoId = jdbc.queryForObject("SELECT repo_id FROM pull_requests WHERE id = ?", UUID.class, prId);
        String deliveryId = "d-" + UUID.randomUUID();
        deliveries.insertReceived(deliveryId, repoId, 42, headSha, "opened");
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO review_publications (id, delivery_id, pr_id, head_sha, status, available_at)
                VALUES (?, ?, ?, ?, 'pending', NOW() - INTERVAL '1 second')
                """, id, deliveryId, prId, headSha);
        return id;
    }
}
