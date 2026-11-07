package com.automatedcodereviewtool.repository;

import com.automatedcodereviewtool.entity.ApiKey;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    /**
     * Loads the owning user in the same query: the API-key filter runs
     * outside any persistence session, so a lazy proxy could not be
     * initialized there.
     */
    @EntityGraph(attributePaths = "user")
    Optional<ApiKey> findByPrefix(String prefix);

    List<ApiKey> findAllByUserIdAndRevokedFalseOrderByCreatedAtDesc(UUID userId);
}
