package com.codelens.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Subset of the GitHub {@code pull_request} webhook payload that we care
 * about. Ignores every other field — GitHub sends a lot of data we don't
 * need and ignoring is forward-compatible.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubWebhookEvent(
        @JsonProperty("action") String action,
        @JsonProperty("pull_request") PrPayload pullRequest,
        @JsonProperty("repository") RepoPayload repository
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PrPayload(
            @JsonProperty("number") int number,
            @JsonProperty("title") String title,
            @JsonProperty("user") UserPayload user,
            @JsonProperty("head") HeadPayload head,
            @JsonProperty("diff_url") String diffUrl,
            @JsonProperty("html_url") String htmlUrl
    ) {
        /** Convenience: pull the head SHA out of {@code head.sha}. */
        public String headSha() {
            return head == null ? null : head.sha();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HeadPayload(
            @JsonProperty("sha") String sha
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RepoPayload(
            @JsonProperty("id") Long id,
            @JsonProperty("full_name") String fullName
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserPayload(
            @JsonProperty("login") String login
    ) {
    }
}
