package com.automatedcodereviewtool.dto;

import com.automatedcodereviewtool.entity.User;

import java.time.Instant;
import java.util.UUID;

/**
 * Public-facing user payload returned by {@code /api/auth/me}.
 * Never includes the GitHub access token.
 */
public record UserResponse(
        UUID id,
        String githubUsername,
        String avatarUrl,
        Instant createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getGithubUsername(),
                user.getAvatarUrl(),
                user.getCreatedAt());
    }
}
