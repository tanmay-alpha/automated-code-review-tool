package com.codelens.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "quality_metrics")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repo_id", nullable = false)
    private Repository repo;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "avg_quality", precision = 5, scale = 2, nullable = false)
    private BigDecimal avgQuality;

    @Column(name = "prs_reviewed", nullable = false)
    private int prsReviewed;

    @Column(name = "critical_count", nullable = false)
    private int criticalCount;

    @Column(name = "major_count", nullable = false)
    private int majorCount;

    @Column(name = "minor_count", nullable = false)
    private int minorCount;
}