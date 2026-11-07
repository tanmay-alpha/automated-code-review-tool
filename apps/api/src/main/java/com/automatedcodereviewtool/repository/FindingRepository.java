package com.automatedcodereviewtool.repository;

import com.automatedcodereviewtool.entity.Finding;
import com.automatedcodereviewtool.entity.Repository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface FindingRepository extends JpaRepository<Finding, UUID> {

    List<Finding> findAllByPullRequestId(UUID pullRequestId);

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
                                               @Param("since") java.time.Instant since);

    interface AntiPatternCount {
        String getPattern();
        long getCnt();
    }
}