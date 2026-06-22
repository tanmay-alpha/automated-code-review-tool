package com.codelens.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Enables {@link org.springframework.scheduling.annotation.Async @Async}
 * on application methods and configures the executor pool used by
 * webhook processing.
 *
 * <p>Bean name {@code taskExecutor} is the qualifier used in
 * {@code @Async("taskExecutor")}.</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("webhook-async-");
        executor.initialize();
        return executor;
    }
}
