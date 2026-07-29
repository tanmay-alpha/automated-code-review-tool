package com.automatedcodereviewtool.repository;

import com.automatedcodereviewtool.entity.DatasetVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DatasetVersionRepository extends JpaRepository<DatasetVersion, UUID> {

    Optional<DatasetVersion> findByNameAndVersion(String name, String version);
}