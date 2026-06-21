package com.codelens.repository;

import com.codelens.entity.PullRequestEntity;
import com.codelens.entity.Repository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PullRequestRepository extends JpaRepository<PullRequestEntity, UUID> {
    Optional<PullRequestEntity> findByRepoAndGithubPrNumber(Repository repo, int githubPrNumber);
}