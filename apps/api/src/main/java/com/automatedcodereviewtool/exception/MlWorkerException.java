package com.codelens.exception;

/**
 * Thrown when the ML worker is unreachable, times out, or returns a
 * server error. Mapped to HTTP 503 by {@link GlobalExceptionHandler}.
 */
public class MlWorkerException extends RuntimeException {

    public MlWorkerException(String message) {
        super(message);
    }

    public MlWorkerException(String message, Throwable cause) {
        super(message, cause);
    }
}