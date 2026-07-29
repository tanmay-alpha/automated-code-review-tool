package com.automatedcodereviewtool.repository;

import com.automatedcodereviewtool.entity.AntiPattern;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AntiPatternRepository extends JpaRepository<AntiPattern, String> {
    List<AntiPattern> findAllByOrderByIdAsc();

    List<AntiPattern> findAllByTrainableOrderByIdAsc(boolean trainable);
}