package com.codelens.repository;

import com.codelens.entity.Repository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RepositoryRepository extends JpaRepository<Repository, UUID> {
    Optional<Repository> findByGithubId(Long githubId);
}
