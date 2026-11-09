package com.automatedcodereviewtool.exception;

/**
 * A request that is well-formed JSON but violates a rule that bean
 * validation cannot express (e.g. a reason required only for some actions,
 * or a rule id that must exist in the taxonomy). Mapped to HTTP 400
 * {@code VALIDATION_FAILED} by {@link GlobalExceptionHandler}; the message
 * is returned to the caller, so it must describe the input only.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
