package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.entity.AntiPattern;
import com.automatedcodereviewtool.repository.AntiPatternRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Canonical taxonomy accessor.
 *
 * <p>The Java side never hardcodes taxonomy IDs. It loads from the
 * {@code anti_patterns} table populated by V5 (which in turn is
 * generated from {@code taxonomy/anti_patterns.yaml}).</p>
 *
 * <p>The methods {@link #require}, {@link #contains},
 * {@link #categoryOf}, {@link #severityOf}, {@link #isTrainable} and
 * {@link #taxonomyVersion} are the only sanctioned access points.
 * Anything that needs a label list must call {@link #trainableIds()} —
 * never derive it from a comment or local array.</p>
 */
@Service
public class TaxonomyService {

    private static final Logger log = LoggerFactory.getLogger(TaxonomyService.class);

    /** Allowed categories — kept here as a safety net for startup validation. */
    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "SECURITY", "PERFORMANCE", "ARCHITECTURE",
            "RELIABILITY", "READABILITY", "MAINTAINABILITY"
    );

    /** Allowed severities — same. */
    private static final Set<String> ALLOWED_SEVERITIES = Set.of(
            "critical", "major", "minor"
    );

    private final AntiPatternRepository repository;
    private final Map<String, AntiPattern> byId = new LinkedHashMap<>();
    private final List<String> trainableOrder;
    private String version = "1.0.0";

    @Autowired
    public TaxonomyService(AntiPatternRepository repository) {
        this.repository = repository;
        try {
            reload();
        } catch (DataAccessException e) {
            // The application may still boot while migrations run, but any
            // caller that needs the taxonomy will surface this as MissingTaxonomy.
            log.warn("TaxonomyService: repository not ready at construction: {}", e.getMessage());
            this.trainableOrder = Collections.emptyList();
        }
    }

    /** Reload the in-memory snapshot from the database. Called once at boot
     *  and again whenever tests / ops need to refresh. */
    public synchronized void reload() {
        List<AntiPattern> rows = repository.findAllByOrderByIdAsc();
        if (rows.isEmpty()) {
            throw new MissingTaxonomyException("anti_patterns table is empty after V5");
        }
        byId.clear();
        for (AntiPattern row : rows) {
            validateRow(row);
            byId.put(row.getId(), row);
        }
        List<String> trainable = rows.stream()
                .filter(AntiPattern::isTrainable)
                .map(AntiPattern::getId)
                .toList();
        if (trainable.isEmpty()) {
            throw new MissingTaxonomyException("no trainable anti-patterns registered");
        }
        this.trainableOrder = Collections.unmodifiableList(trainable);
        log.info("TaxonomyService loaded {} patterns ({} trainable)",
                byId.size(), trainableOrder.size());
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

    /** Return the anti-pattern or throw if unknown. */
    public AntiPattern require(String id) {
        AntiPattern row = byId.get(id);
        if (row == null) {
            throw new NoSuchElementException("Unknown anti-pattern id: " + id);
        }
        return row;
    }

    /** True if the id is known to the taxonomy. */
    public boolean contains(String id) {
        return id != null && byId.containsKey(id);
    }

    /** Category of an id. Returns null for unknown ids so callers can decide. */
    public String categoryOf(String id) {
        AntiPattern row = byId.get(id);
        return row == null ? null : row.getCategory();
    }

    /** Severity of an id. Returns null for unknown ids. */
    public String severityOf(String id) {
        AntiPattern row = byId.get(id);
        return row == null ? null : row.getDefaultSeverity();
    }

    /** True if the id is trainable. Returns false for unknown ids. */
    public boolean isTrainable(String id) {
        AntiPattern row = byId.get(id);
        return row != null && row.isTrainable();
    }

    /** Deterministic ordered list of trainable label ids. Frozen at load time. */
    public List<String> trainableIds() {
        return trainableOrder;
    }

    /** All known ids, deterministic order. */
    public List<String> allIds() {
        return List.copyOf(byId.keySet());
    }

    /** The taxonomy version string recorded by V5. */
    public String taxonomyVersion() {
        return version;
    }

    /** Update the version if config overrides it (mostly for tests). */
    public void setVersionForTesting(String v) {
        this.version = v;
    }

    /** Thrown when the taxonomy is missing or malformed. */
    public static class MissingTaxonomyException extends RuntimeException {
        public MissingTaxonomyException(String message) {
            super(message);
        }
    }
}