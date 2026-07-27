package com.codelens.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response from {@code POST https://github.com/login/oauth/access_token}.
 * GitHub returns snake_case keys; only the three we need are mapped.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("scope") String scope
) {
}
