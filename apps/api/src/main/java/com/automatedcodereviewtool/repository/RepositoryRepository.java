package com.automatedcodereviewtool.repository;

import com.automatedcodereviewtool.entity.Repository;
import com.automatedcodereviewtool.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link com.automatedcodereviewtool.entity.Repository Repository} entities.
 *
 * <p>The class name shadows {@code org.springframework.stereotype.Repository}
 * so we don't import that annotation here — Spring Data registers the
 * bean automatically.</p>
 */
public interface RepositoryRepository extends JpaRepository<Repository, UUID> {
    Optional<Repository> findByGithubId(Long githubId);

    List<Repository> findAllByOwnerAndIsActiveTrue(User owner);
}