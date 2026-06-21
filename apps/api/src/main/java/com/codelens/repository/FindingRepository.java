package com.codelens.repository;

import com.codelens.entity.Finding;
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
}