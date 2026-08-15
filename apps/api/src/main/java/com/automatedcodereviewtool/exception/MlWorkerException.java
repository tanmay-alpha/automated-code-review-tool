package com.automatedcodereviewtool.exception;

/**
 * Thrown when the ML worker is unreachable, times out, or returns a
 * server error. Mapped to HTTP 503 by {@link GlobalExceptionHandler}.
 */
public class MlWorkerException extends RuntimeException {

    private final Integer upstreamStatus;
    private final boolean retryable;

    public MlWorkerException(String message) {
        super(message);
        this.upstreamStatus = null;
        this.retryable = true;
    }

    public MlWorkerException(String message, Throwable cause) {
        super(message, cause);
        this.upstreamStatus = null;
        this.retryable = true;
    }

    public MlWorkerException(String message, Integer upstreamStatus, boolean retryable) {
        super(message);
        this.upstreamStatus = upstreamStatus;
        this.retryable = retryable;
    }

    public MlWorkerException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.upstreamStatus = null;
        this.retryable = retryable;
    }

    public Integer getUpstreamStatus() {
        return upstreamStatus;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
