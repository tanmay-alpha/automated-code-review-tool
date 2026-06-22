package com.codelens.repository;

import com.codelens.entity.QualityMetric;
import com.codelens.entity.Repository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface QualityMetricRepository extends JpaRepository<QualityMetric, UUID> {
    Optional<QualityMetric> findByRepoAndDate(Repository repo, LocalDate date);
}