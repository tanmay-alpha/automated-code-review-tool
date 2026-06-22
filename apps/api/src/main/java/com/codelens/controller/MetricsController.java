package com.codelens.controller;

import com.codelens.dto.response.QualityTrendResponse;
import com.codelens.entity.QualityMetric;
import com.codelens.entity.Repository;
import com.codelens.entity.User;
import com.codelens.repository.FindingRepository;
import com.codelens.repository.RepositoryRepository;
import com.codelens.service.RepoService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Read-only metrics for the dashboard.
 *
 * <p>{@code GET /api/metrics/quality-trend?repo=…&days=30} —
 * daily quality scores plus the single most-frequent anti-pattern
 * found in that window. Powers the line chart in
 * {@code apps/web/app/dashboard/repo/[id]/page.tsx} (issue #18).</p>
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final RepoService repoService;
    private final RepositoryRepository repositoryRepository;
    private final FindingRepository findingRepository;

    public MetricsController(RepoService repoService,
                             RepositoryRepository repositoryRepository,
                             FindingRepository findingRepository) {
        this.repoService = repoService;
        this.repositoryRepository = repositoryRepository;
        this.findingRepository = findingRepository;
    }

    @GetMapping("/quality-trend")
    public QualityTrendResponse qualityTrend(@AuthenticationPrincipal User user,
                                             @RequestParam UUID repo,
                                             @RequestParam(defaultValue = "30") int days) {
        Repository repoEntity = repositoryRepository.findById(repo)
                .orElseThrow(() -> new EntityNotFoundException("Repo " + repo + " not found"));
        if (!repoEntity.getOwner().getId().equals(user.getId())) {
            throw new EntityNotFoundException("Repo " + repo + " not found");
        }

        List<QualityMetric> window = repoService.qualityTrend(user, repo, days);
        List<QualityTrendResponse.Point> points = window.stream()
                .map(qm -> new QualityTrendResponse.Point(
                        qm.getDate(),
                        qm.getAvgQuality(),
                        qm.getPrsReviewed(),
                        qm.getCriticalCount(),
                        qm.getMajorCount(),
                        qm.getMinorCount()))
                .toList();

        String topPattern = findingRepository.findTopAntiPatterns(repoEntity).stream()
                .findFirst()
                .map(FindingRepository.AntiPatternCount::getPattern)
                .orElse(null);

        return new QualityTrendResponse(
                repoEntity.getId(),
                repoEntity.getFullName(),
                points,
                topPattern);
    }
}
