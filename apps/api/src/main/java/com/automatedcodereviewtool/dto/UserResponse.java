package com.codelens.dto;

import com.codelens.entity.User;

import java.time.Instant;
import java.util.UUID;

/**
 * Public-facing user payload returned by {@code /api/auth/me}.
 * Never includes the API-key hash or the GitHub access token.
 */
public record UserResponse(
        UUID id,
        String githubUsername,
        String avatarUrl,
        String apiKeyPrefix,
        Instant createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getGithubUsername(),
                user.getAvatarUrl(),
                user.getApiKeyPrefix(),
                user.getCreatedAt());
    }
}
