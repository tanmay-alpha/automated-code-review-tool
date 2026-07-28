package com.automatedcodereviewtool;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * automated-code-review-tool API gateway entry point.
 *
 * Hosts the OAuth flow, webhook handlers, and proxies review requests
 * to the ml-worker service.
 */
@SpringBootApplication
@EnableScheduling
public class AutomatedCodeReviewToolApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutomatedCodeReviewToolApplication.class, args);
    }
}
