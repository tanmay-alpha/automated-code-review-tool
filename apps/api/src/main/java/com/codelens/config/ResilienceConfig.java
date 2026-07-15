package com.codelens.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configuration for Resilience4j circuit breakers.
 *
 * This configuration provides circuit breakers for:
 * - Redis rate limiting (fallback to in-memory rate limiting)
 * - ML worker service calls
 */
@Configuration
public class ResilienceConfig {

    /**
     * Circuit breaker for Redis operations during rate limiting.
     * Opens after 5 consecutive failures, with a 60-second wait.
     */
    @Bean
    public CircuitBreaker redisRateLimiterCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50) // Open after 50% failures
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .permittedNumberOfCallsInHalfOpenState(2)
                .slidingWindowSize(5)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .build();

        return CircuitBreaker.of("redisRateLimiter", config);
    }

    /**
     * Circuit breaker for ML worker service calls.
     * Opens after 3 consecutive failures, with a 30-second wait.
     */
    @Bean
    public CircuitBreaker mlWorkerCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .slidingWindowSize(3)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .build();

        return CircuitBreaker.of("mlWorker", config);
    }

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        return CircuitBreakerRegistry.of(redisRateLimiterCircuitBreaker().getCircuitBreakerConfig());
    }
}