package com.automatedcodereviewtool.service;

import java.time.Instant;

/**
 * A classified GitHub REST failure. The {@link Kind} decides whether the
 * caller retries and when; {@link #code()} is the stable, explainable
 * identifier stored on the delivery or publication that failed.
 */
public class GitHubApiException extends RuntimeException {

    public enum Kind {
        /** 401: the stored OAuth token was revoked or expired. Terminal. */
        AUTH_REVOKED("github_auth_revoked", false),
        /** 404: repository, pull request or comment no longer visible. Terminal. */
        NOT_FOUND("github_not_found", false),
        /** 403 without rate-limit signals: token lacks permission. Terminal. */
        FORBIDDEN("github_forbidden", false),
        /** 403/429 primary or secondary rate limit. Retry at {@link #retryAt()}. */
        RATE_LIMITED("github_rate_limited", true),
        /** 406/422 on a diff request: GitHub refuses to render the diff. Terminal. */
        DIFF_TOO_LARGE("diff_too_large", false),
        /** 422 on a write: GitHub rejected the payload. Terminal for that payload. */
        UNPROCESSABLE("github_unprocessable", false),
        /** 5xx, I/O error, timeout, or another unexpected status. Retry with backoff. */
        TRANSIENT("github_unavailable", true);

        private final String code;
        private final boolean retryable;

        Kind(String code, boolean retryable) {
            this.code = code;
            this.retryable = retryable;
        }

        public String code() {
            return code;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private final Kind kind;
    private final int status;
    private final Instant retryAt;

    public GitHubApiException(Kind kind, int status, Instant retryAt, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.status = status;
        this.retryAt = retryAt;
    }

    public Kind kind() {
        return kind;
    }

    /** HTTP status, or 0 when no response was received. */
    public int status() {
        return status;
    }

    /** Earliest time a retry may succeed; only set for {@link Kind#RATE_LIMITED}. */
    public Instant retryAt() {
        return retryAt;
    }

    public String code() {
        return kind.code();
    }

    public boolean retryable() {
        return kind.retryable();
    }

    /** {@code code: message}, the form persisted as a delivery or publication error. */
    public String describe() {
        return kind.code() + ": " + getMessage();
    }
}
