package com.codelens.controller;

import com.codelens.dto.request.CreateApiKeyRequest;
import com.codelens.dto.response.ApiKeyResponse;
import com.codelens.dto.response.CreatedApiKeyResponse;
import com.codelens.entity.ApiKey;
import com.codelens.entity.User;
import com.codelens.service.ApiKeyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * User-facing API key management (issue #15).
 *
 * <p>All endpoints require a valid JWT — keys are issued to a logged-in
 * user and bound to their account. The plaintext key is shown only
 * once, in the response of {@code POST /api/auth/api-keys}; subsequent
 * {@code GET} calls return only the prefix.</p>
 */
@RestController
@RequestMapping("/api/auth/api-keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    // POST /api/auth/api-keys
    @PostMapping
    public ResponseEntity<CreatedApiKeyResponse> create(@AuthenticationPrincipal User user,
                                                        @Valid @RequestBody CreateApiKeyRequest request) {
        ApiKeyService.CreatedApiKey result = apiKeyService.createKey(user, request.label());
        ApiKey k = result.apiKey();
        CreatedApiKeyResponse body = new CreatedApiKeyResponse(
                k.getId(), k.getLabel(), k.getPrefix(), result.plaintext(), k.getCreatedAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    // GET /api/auth/api-keys
    @GetMapping
    public List<ApiKeyResponse> list(@AuthenticationPrincipal User user) {
        return apiKeyService.listKeys(user).stream()
                .map(k -> new ApiKeyResponse(
                        k.getId(), k.getLabel(), k.getPrefix(), k.getCreatedAt(), k.getLastUsedAt()))
                .toList();
    }

    // DELETE /api/auth/api-keys/{id}
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        apiKeyService.revokeKey(user, id);
        return ResponseEntity.noContent().build();
    }
}
