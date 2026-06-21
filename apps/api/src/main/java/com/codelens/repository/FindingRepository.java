package com.codelens.repository;

import com.codelens.entity.Finding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FindingRepository extends JpaRepository<Finding, UUID> {
}