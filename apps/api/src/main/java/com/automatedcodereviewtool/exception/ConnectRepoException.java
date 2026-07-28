package com.automatedcodereviewtool.exception;

/**
 * Thrown when {@link com.automatedcodereviewtool.service.RepoService#connect}
 * cannot complete — e.g. the user does not own the repo, the
 * GitHub App is not installed, or the user has revoked the
 * authorization. Mapped to HTTP 400 by
 * {@link com.automatedcodereviewtool.exception.GlobalExceptionHandler}.
 */
public class ConnectRepoException extends RuntimeException {

    public ConnectRepoException(String message) {
        super(message);
    }

    public ConnectRepoException(String message, Throwable cause) {
        super(message, cause);
    }
}
