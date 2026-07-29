package com.automatedcodereviewtool.repository;

import com.automatedcodereviewtool.entity.Annotation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AnnotationRepository extends JpaRepository<Annotation, UUID> {

    long countByCodeSampleIdAndLabelState(UUID codeSampleId, String labelState);

    boolean existsBySource(String source);

    List<Annotation> findByCodeSampleId(UUID codeSampleId);
}