package com.automatedcodereviewtool.controller;

import com.automatedcodereviewtool.dto.response.FindingSummaryResponse;
import com.automatedcodereviewtool.dto.response.QualityTrendResponse;
import com.automatedcodereviewtool.entity.QualityMetric;
import com.automatedcodereviewtool.entity.Repository;
import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.service.RepoService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Read-only metrics for the dashboard. Both endpoints are owner-scoped
 * (404 for a repo the caller does not own) and accept {@code days} in 1..365.
 *
 * <ul>
 *   <li>{@code GET /api/metrics/quality-trend?repo=…&days=30} — daily
 *       quality scores plus the single most-frequent anti-pattern found in
 *       that window. Powers the line chart in
 *       {@code apps/web/src/app/dashboard/page.tsx}.</li>
 *   <li>{@code GET /api/metrics/finding-summary?repo=…&days=30} — triage
 *       outcome per rule and finding count per detector engine.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/metrics")
@Validated
public class MetricsController {

    private final RepoService repoService;

    public MetricsController(RepoService repoService) {
        this.repoService = repoService;
    }

    @GetMapping("/quality-trend")
    public QualityTrendResponse qualityTrend(@AuthenticationPrincipal User user,
                                             @RequestParam UUID repo,
                                             @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days) {
        Repository repoEntity = repoService.requireOwned(user, repo);
        List<QualityMetric> window = repoService.qualityTrend(repoEntity, days);
        List<QualityTrendResponse.Point> points = window.stream()
                .map(qm -> new QualityTrendResponse.Point(
                        qm.getDate(),
                        qm.getAvgQuality(),
                        qm.getPrsReviewed(),
                        qm.getCriticalCount(),
                        qm.getMajorCount(),
                        qm.getMinorCount()))
                .toList();

        String topPattern = repoService.topAntiPattern(repoEntity, days);

        return new QualityTrendResponse(
                repoEntity.getId(),
                repoEntity.getFullName(),
                points,
                topPattern);
    }

    @GetMapping("/finding-summary")
    public FindingSummaryResponse findingSummary(@AuthenticationPrincipal User user,
                                                 @RequestParam UUID repo,
                                                 @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days) {
        return repoService.findingSummary(repoService.requireOwned(user, repo), days);
    }
}
