package com.automatedcodereviewtool.repository;

import com.automatedcodereviewtool.entity.PredictionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PredictionEventRepository extends JpaRepository<PredictionEvent, UUID> {

    List<PredictionEvent> findByPullRequestIdOrderByCreatedAtDesc(UUID pullRequestId);

    List<PredictionEvent> findByStatusOrderByCreatedAtDesc(String status);
}
