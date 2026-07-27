package com.codelens.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Subset of {@code GET https://api.github.com/user} that we persist on
 * the {@code users} row.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubUserInfo(
        @JsonProperty("id") Long id,
        @JsonProperty("login") String login,
        @JsonProperty("avatar_url") String avatarUrl
) {
}
