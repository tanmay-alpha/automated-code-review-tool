package com.automatedcodereviewtool.repository;

import com.automatedcodereviewtool.entity.Finding;
import com.automatedcodereviewtool.entity.Repository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FindingRepository extends JpaRepository<Finding, UUID> {

    List<Finding> findAllByPullRequestId(UUID pullRequestId);

    /**
     * The finding, only when its PR's repository is owned by {@code ownerId}.
     * Ownership is decided in SQL (one query) instead of walking lazy
     * finding → PR → repo → owner associations.
     */
    @Query("""
            SELECT f FROM Finding f
            WHERE f.id = :id
              AND f.pullRequest.repo.owner.id = :ownerId
            """)
    Optional<Finding> findOwnedById(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    /**
     * Bulk delete all findings tied to a specific PR. Used when a PR
     * is re-reviewed for a new head SHA — old findings would otherwise
     * stack up. Returns the number of rows deleted.
     */
    @Modifying
    @Query("delete from Finding f where f.pullRequest.id = :prId")
    int deleteAllByPullRequestId(@Param("prId") UUID pullRequestId);

    /**
     * Anti-pattern frequency across a repo's findings created since
     * {@code since}, most frequent first. The caller takes the first row.
     */
    @Query("""
            SELECT f.antiPattern AS pattern, COUNT(f) AS cnt
            FROM Finding f
            WHERE f.pullRequest.repo = :repo
              AND f.createdAt >= :since
            GROUP BY f.antiPattern
            ORDER BY COUNT(f) DESC
            """)
    List<AntiPatternCount> findTopAntiPatterns(@Param("repo") Repository repo,
                                               @Param("since") Instant since);

    /** Per-rule triage breakdown of a repo's findings created since {@code since}, most frequent first. */
    @Query("""
            SELECT f.antiPattern AS antiPattern,
                   COUNT(f) AS total,
                   SUM(CASE WHEN f.status = 'open' THEN 1 ELSE 0 END) AS openCount,
                   SUM(CASE WHEN f.status = 'accepted' THEN 1 ELSE 0 END) AS acceptedCount,
                   SUM(CASE WHEN f.status = 'dismissed' THEN 1 ELSE 0 END) AS dismissedCount,
                   SUM(CASE WHEN f.status = 'fixed' THEN 1 ELSE 0 END) AS fixedCount,
                   SUM(CASE WHEN f.status = 'false_positive' THEN 1 ELSE 0 END) AS falsePositiveCount
            FROM Finding f
            WHERE f.pullRequest.repo = :repo
              AND f.createdAt >= :since
            GROUP BY f.antiPattern
            ORDER BY COUNT(f) DESC, f.antiPattern ASC
            """)
    List<RuleTriageCount> summarizeByRule(@Param("repo") Repository repo,
                                          @Param("since") Instant since);

    /** Finding count per detector engine since {@code since}; a null engine is returned as null. */
    @Query("""
            SELECT f.engine AS engine, COUNT(f) AS total
            FROM Finding f
            WHERE f.pullRequest.repo = :repo
              AND f.createdAt >= :since
            GROUP BY f.engine
            ORDER BY COUNT(f) DESC
            """)
    List<EngineCount> summarizeByEngine(@Param("repo") Repository repo,
                                        @Param("since") Instant since);

    interface AntiPatternCount {
        String getPattern();
        long getCnt();
    }

    interface RuleTriageCount {
        String getAntiPattern();
        long getTotal();
        long getOpenCount();
        long getAcceptedCount();
        long getDismissedCount();
        long getFixedCount();
        long getFalsePositiveCount();
    }

    interface EngineCount {
        String getEngine();
        long getTotal();
    }
}
