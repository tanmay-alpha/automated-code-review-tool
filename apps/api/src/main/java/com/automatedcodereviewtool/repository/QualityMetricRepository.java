package com.automatedcodereviewtool.repository;

import com.automatedcodereviewtool.entity.QualityMetric;
import com.automatedcodereviewtool.entity.Repository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
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

    /**
     * Sum of the daily averages and number of daily rows per repository in
     * a date window, for several repositories in one query.
     */
    @Query("""
            SELECT qm.repo.id AS repoId,
                   SUM(qm.avgQuality) AS qualitySum,
                   COUNT(qm) AS dayCount
            FROM QualityMetric qm
            WHERE qm.repo IN :repos
              AND qm.date BETWEEN :from AND :to
            GROUP BY qm.repo.id
            """)
    List<QualityWindow> summarizeRange(@Param("repos") Collection<Repository> repos,
                                       @Param("from") LocalDate from,
                                       @Param("to") LocalDate to);

    interface QualityWindow {
        UUID getRepoId();
        BigDecimal getQualitySum();
        long getDayCount();
    }
}
