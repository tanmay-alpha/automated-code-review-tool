package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.entity.AntiPattern;
import com.automatedcodereviewtool.repository.AntiPatternRepository;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory snapshot of the {@code anti_patterns} table, which Flyway
 * keeps in sync with {@code taxonomy/anti_patterns.yaml} (V17).
 *
 * <p>Loaded and validated once at startup by
 * {@link com.automatedcodereviewtool.config.TaxonomyStartupValidator} so a
 * missing or malformed taxonomy stops the application from booting.</p>
 */
@Service
public class TaxonomyService {

    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "SECURITY", "PERFORMANCE", "ARCHITECTURE",
            "RELIABILITY", "READABILITY", "MAINTAINABILITY"
    );

    private static final Set<String> ALLOWED_SEVERITIES = Set.of(
            "critical", "major", "minor"
    );

    private final AntiPatternRepository repository;
    /** Immutable snapshot, swapped atomically by {@link #reload()} so readers never see a partial map. */
    private volatile Map<String, AntiPattern> byId = Map.of();
    private volatile List<String> trainableOrder = Collections.emptyList();

    public TaxonomyService(AntiPatternRepository repository) {
        this.repository = repository;
    }

    /** Reload the in-memory snapshot from the database, validating every row. */
    public synchronized void reload() {
        List<AntiPattern> rows = repository.findAllByOrderByIdAsc();
        if (rows.isEmpty()) {
            throw new MissingTaxonomyException("anti_patterns table is empty");
        }
        Map<String, AntiPattern> loaded = new LinkedHashMap<>();
        for (AntiPattern row : rows) {
            validateRow(row);
            loaded.put(row.getId(), row);
        }
        List<String> trainable = rows.stream()
                .filter(AntiPattern::isTrainable)
                .map(AntiPattern::getId)
                .toList();
        if (trainable.isEmpty()) {
            throw new MissingTaxonomyException("no trainable anti-patterns registered");
        }
        this.byId = Collections.unmodifiableMap(loaded);
        this.trainableOrder = Collections.unmodifiableList(trainable);
    }

    private void validateRow(AntiPattern row) {
        if (row.getId() == null || row.getId().isBlank()) {
            throw new MissingTaxonomyException("anti_pattern row has blank id");
        }
        if (!ALLOWED_CATEGORIES.contains(row.getCategory())) {
            throw new MissingTaxonomyException(
                    "anti_pattern '" + row.getId() + "' has invalid category '" + row.getCategory() + "'");
        }
        if (!ALLOWED_SEVERITIES.contains(row.getDefaultSeverity())) {
            throw new MissingTaxonomyException(
                    "anti_pattern '" + row.getId() + "' has invalid severity '" + row.getDefaultSeverity() + "'");
        }
    }

    /** Trainable label ids, ordered by id. */
    public List<String> trainableIds() {
        return trainableOrder;
    }

    /** All known ids, ordered by id. */
    public List<String> allIds() {
        return List.copyOf(byId.keySet());
    }

    /**
     * The taxonomy row for {@code id}, or empty when the id is unknown.
     * Loads the snapshot on first use if the startup validator has not run yet.
     */
    public Optional<AntiPattern> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        if (byId.isEmpty()) {
            reload();
        }
        return Optional.ofNullable(byId.get(id));
    }

    /** Thrown when the taxonomy is missing or malformed. */
    public static class MissingTaxonomyException extends RuntimeException {
        public MissingTaxonomyException(String message) {
            super(message);
        }
    }
}
