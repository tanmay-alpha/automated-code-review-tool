package com.codelens.repository;

import com.codelens.entity.Repository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link com.codelens.entity.Repository Repository} entities.
 *
 * <p>The class name shadows {@code org.springframework.stereotype.Repository}
 * so we don't import that annotation here — Spring Data registers the
 * bean automatically.</p>
 */
public interface RepositoryRepository extends JpaRepository<Repository, UUID> {
    Optional<Repository> findByGithubId(Long githubId);
}