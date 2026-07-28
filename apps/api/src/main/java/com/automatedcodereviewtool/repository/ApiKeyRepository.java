package com.automatedcodereviewtool.repository;

import com.automatedcodereviewtool.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByPrefix(String prefix);

    List<ApiKey> findAllByUserId(UUID userId);
}
