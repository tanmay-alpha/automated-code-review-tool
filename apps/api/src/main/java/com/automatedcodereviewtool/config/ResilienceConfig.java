package com.automatedcodereviewtool.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import com.automatedcodereviewtool.exception.InvalidDiffException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Resilience4j circuit breakers, built directly (no Spring auto-config):
 * one guarding the Redis-backed API-key rate limiter, one guarding ML
 * worker calls. Both use a count-based window and open when at least
 * half of the recent calls in the window failed.
 */
@Configuration
public class ResilienceConfig {

    /** Redis rate limiting: 5-call window, stays open for 60 s. */
    @Bean
    public CircuitBreaker redisRateLimiterCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .permittedNumberOfCallsInHalfOpenState(2)
                .slidingWindowSize(5)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .build();

        return CircuitBreaker.of("redisRateLimiter", config);
    }

    /**
     * ML worker calls: 3-call window, stays open for 30 s. A 4xx from the
     * worker ({@link InvalidDiffException}) is a bad input, not an outage,
     * so it does not count as a failure.
     */
    @Bean
    public CircuitBreaker mlWorkerCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .slidingWindowSize(3)
                .minimumNumberOfCalls(3)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .ignoreExceptions(InvalidDiffException.class)
                .build();

        return CircuitBreaker.of("mlWorker", config);
    }
}
