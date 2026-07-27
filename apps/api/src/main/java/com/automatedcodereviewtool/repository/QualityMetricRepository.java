package com.codelens.repository;

import com.codelens.entity.QualityMetric;
import com.codelens.entity.Repository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QualityMetricRepository extends JpaRepository<QualityMetric, UUID> {

    Optional<QualityMetric> findByRepoAndDate(Repository repo, LocalDate date);

    /**
     * Returns the daily metrics for a repo in a given date window,
     * ordered oldest → newest so the chart can render in one pass.
     */
    @Query("""
            SELECT qm FROM QualityMetric qm
            WHERE qm.repo = :repo
              AND qm.date BETWEEN :from AND :to
            ORDER BY qm.date ASC
            """)
    List<QualityMetric> findRange(@Param("repo") Repository repo,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to);
}