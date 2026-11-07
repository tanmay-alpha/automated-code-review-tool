package com.automatedcodereviewtool.controller;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the refresh-rotation compare-and-set script against a real Redis.
 * Tagged {@code postgres} so it runs with the other container-backed tests
 * in the {@code postgres-correctness} profile.
 */
@Tag("postgres")
@Testcontainers
class RefreshRotationRedisTest {

    private static final String KEY = "session:refresh:user-1";
    private static final Duration TTL = Duration.ofDays(7);

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void reset() {
        redis.delete(KEY);
    }

    @Test
    void rotatesOnlyFromTheCurrentTokenAndRevokesOnReuse() {
        redis.opsForValue().set(KEY, "refresh-a");

        assertThat(AuthController.rotateRefreshSession(redis, KEY, "refresh-a", "refresh-b", TTL)).isTrue();
        assertThat(redis.opsForValue().get(KEY)).isEqualTo("refresh-b");
        assertThat(redis.getExpire(KEY)).isBetween(TTL.toSeconds() - 5, TTL.toSeconds());

        // Replaying the rotated token revokes the session...
        assertThat(AuthController.rotateRefreshSession(redis, KEY, "refresh-a", "refresh-c", TTL)).isFalse();
        assertThat(redis.hasKey(KEY)).isFalse();
        // ...so even the legitimately current token no longer works.
        assertThat(AuthController.rotateRefreshSession(redis, KEY, "refresh-b", "refresh-d", TTL)).isFalse();
        assertThat(redis.hasKey(KEY)).isFalse();
    }

    @Test
    void concurrentRefreshesWithTheSameTokenRotateAtMostOnce() throws Exception {
        int contenders = 8;
        for (int round = 0; round < 20; round++) {
            redis.opsForValue().set(KEY, "refresh-current");
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(contenders);
            try {
                List<Future<Boolean>> results = new ArrayList<>();
                for (int i = 0; i < contenders; i++) {
                    String next = "refresh-next-" + i;
                    Callable<Boolean> rotate = () -> {
                        start.await();
                        return AuthController.rotateRefreshSession(redis, KEY, "refresh-current", next, TTL);
                    };
                    results.add(executor.submit(rotate));
                }
                start.countDown();
                int winners = 0;
                for (Future<Boolean> result : results) {
                    if (result.get()) {
                        winners++;
                    }
                }
                assertThat(winners).isEqualTo(1);
            } finally {
                executor.shutdownNow();
            }
        }
    }
}
