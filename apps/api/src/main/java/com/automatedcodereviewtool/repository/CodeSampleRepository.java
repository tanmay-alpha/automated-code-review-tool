package com.automatedcodereviewtool.repository;

import com.automatedcodereviewtool.entity.CodeSample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CodeSampleRepository extends JpaRepository<CodeSample, UUID> {

    Optional<CodeSample> findByPullRequestIdAndCommitShaAndFilePathAndNewStartAndContentSha256(
            UUID pullRequestId,
            String commitSha,
            String filePath,
            int newStart,
            String contentSha256);
}