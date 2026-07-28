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
     * Returns the most-frequent anti-pattern across a repo's reviewed
     * PRs. JPQL has no native "group by" with limit, so we return the
     * top-K and let the service take the first row.
     */
    @Query("""
            SELECT f.antiPattern AS pattern, COUNT(f) AS cnt
            FROM Finding f
            WHERE f.pullRequest.repo = :repo
            GROUP BY f.antiPattern
            ORDER BY COUNT(f) DESC
            """)
    List<AntiPatternCount> findTopAntiPatterns(@Param("repo") Repository repo);

    interface AntiPatternCount {
        String getPattern();
        long getCnt();
    }

    @Query("""
            SELECT new com.automatedcodereviewtool.dto.FindingStats(
                f.antiPattern, f.severity, COUNT(f)
            )
            FROM Finding f
            WHERE f.pullRequest.repo.id = :repoId
            GROUP BY f.antiPattern, f.severity
            """)
    List<com.automatedcodereviewtool.dto.FindingStats> aggregateByAntiPatternAndSeverity(@Param("repoId") UUID repoId);

    @Query("""
            SELECT new com.automatedcodereviewtool.dto.FindingTrendRow(
                FUNCTION('DATE', f.pullRequest.createdAt), COUNT(f), f.severity
            )
            FROM Finding f
            WHERE f.pullRequest.repo.id = :repoId
              AND f.pullRequest.createdAt >= :since
            GROUP BY FUNCTION('DATE', f.pullRequest.createdAt), f.severity
            ORDER BY FUNCTION('DATE', f.pullRequest.createdAt) ASC
            """)
    List<com.automatedcodereviewtool.dto.FindingTrendRow> trendByDay(@Param("repoId") UUID repoId,
                                                       @Param("since") java.time.Instant since);
}