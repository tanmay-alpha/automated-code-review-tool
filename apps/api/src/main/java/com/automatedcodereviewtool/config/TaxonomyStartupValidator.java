package com.automatedcodereviewtool.config;

import com.automatedcodereviewtool.service.TaxonomyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Validates the canonical taxonomy at application startup.
 *
 * <p>As an {@link ApplicationRunner} it runs once the context has
 * refreshed, i.e. after the embedded web server has started. If the
 * {@code anti_patterns} table is empty or contains malformed rows
 * (invalid category/severity), the exception aborts startup and the
 * application exits with a clear error.</p>
 */
@Component
@Order(0)
public class TaxonomyStartupValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TaxonomyStartupValidator.class);

    private final TaxonomyService taxonomy;

    public TaxonomyStartupValidator(TaxonomyService taxonomy) {
        this.taxonomy = taxonomy;
    }

    @Override
    public void run(ApplicationArguments args) {
        taxonomy.reload();
        int total = taxonomy.allIds().size();
        int trainable = taxonomy.trainableIds().size();
        if (total == 0 || trainable == 0) {
            throw new IllegalStateException(
                    "Taxonomy validation failed: total=" + total + " trainable=" + trainable);
        }
        log.info("Taxonomy validated: total={} trainable={}", total, trainable);
    }
}