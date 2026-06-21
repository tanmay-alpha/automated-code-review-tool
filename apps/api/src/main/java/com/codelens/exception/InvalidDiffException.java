package com.codelens.exception;

/**
 * Thrown when the ML worker rejects a request with a 4xx — typically
 * an empty or malformed diff. Mapped to HTTP 422 by
 * {@link GlobalExceptionHandler}.
 */
public class InvalidDiffException extends RuntimeException {

    private final int upstreamStatus;

    public InvalidDiffException(String message) {
        super(message);
        this.upstreamStatus = 422;
    }

    public InvalidDiffException(int upstreamStatus, String message) {
        super(message);
        this.upstreamStatus = upstreamStatus;
    }

    public int getUpstreamStatus() {
        return upstreamStatus;
    }
}