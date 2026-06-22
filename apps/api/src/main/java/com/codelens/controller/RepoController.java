package com.codelens.controller;

import com.codelens.dto.request.ConnectRepoRequest;
import com.codelens.dto.response.RepoResponse;
import com.codelens.entity.PullRequestEntity;
import com.codelens.entity.Repository;
import com.codelens.entity.User;
import com.codelens.repository.RepositoryRepository;
import com.codelens.security.JwtAuthFilter;
import com.codelens.service.RepoService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Repo management: connect / disconnect / list. All endpoints
 * require JWT auth (handled by {@link JwtAuthFilter}) and operate
 * on the {@link User} bound to the request.
 */
@RestController
@RequestMapping("/api/repos")
public class RepoController {

    private final RepoService repoService;
    private final RepositoryRepository repositoryRepository;

    public RepoController(RepoService repoService, RepositoryRepository repositoryRepository) {
        this.repoService = repoService;
        this.repositoryRepository = repositoryRepository;
    }

    // POST /api/repos/connect
    @PostMapping("/connect")
    public ResponseEntity<RepoResponse> connect(@AuthenticationPrincipal User user,
                                                @Valid @RequestBody ConnectRepoRequest request) {
        RepoService.ConnectResult result = repoService.connect(user, request.fullName());
        // Body is the repo; the secret is returned via the X-Webhook-Secret
        // header because it is single-use and we want it visible in the
        // response viewer / fetch interceptor.
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("X-Webhook-Secret", result.plaintextSecret())
                .body(RepoResponse.from(result.repo()));
    }

    // GET /api/repos
    @GetMapping
    public List<RepoResponse> list(@AuthenticationPrincipal User user) {
        return repoService.listForOwner(user).stream()
                .map(RepoResponse::from)
                .toList();
    }

    // GET /api/repos/{id}
    @GetMapping("/{id}")
    public RepoResponse get(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        Repository repo = requireOwned(user, id);
        return RepoResponse.from(repo);
    }

    // DELETE /api/repos/{id}
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> disconnect(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        repoService.disconnect(user, id);
        return ResponseEntity.noContent().build();
    }

    // GET /api/repos/{id}/prs
    @GetMapping("/{id}/prs")
    public Page<PullRequestEntity> listPrs(@AuthenticationPrincipal User user,
                                           @PathVariable UUID id,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return repoService.listPrs(user, id,
                Math.max(0, page),
                Math.min(100, Math.max(1, size)));
    }

    // helper
    private Repository requireOwned(User caller, UUID repoId) {
        Repository repo = repositoryRepository.findById(repoId)
                .orElseThrow(() -> new EntityNotFoundException("Repo " + repoId + " not found"));
        if (!repo.getOwner().getId().equals(caller.getId())) {
            // 404 to avoid leaking existence of repos the caller doesn't own
            throw new EntityNotFoundException("Repo " + repoId + " not found");
        }
        return repo;
    }
}
