package com.codelens.exception;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * Maps service-layer exceptions to uniform JSON error responses.
 *
 * <p><b>Never</b> leaks the underlying stack trace or message beyond
 * what is safe to expose to the caller. The stack trace goes to logs,
 * not to the response body.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MlWorkerException.class)
    public ResponseEntity<Map<String, Object>> handleMlWorker(MlWorkerException ex) {
        log.warn("ML worker error: {}", ex.getMessage());
        return body(HttpStatus.SERVICE_UNAVAILABLE, "ML_WORKER_UNAVAILABLE", ex.getMessage());
    }

    @ExceptionHandler(InvalidDiffException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidDiff(InvalidDiffException ex) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DIFF", ex.getMessage());
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(EntityNotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(AccessDeniedException ex) {
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
    }

    @ExceptionHandler(ConnectRepoException.class)
    public ResponseEntity<Map<String, Object>> handleConnectRepo(ConnectRepoException ex) {
        log.warn("Repo connect failed: {}", ex.getMessage());
        return body(HttpStatus.BAD_REQUEST, "CONNECT_REPO_FAILED", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        // Concatenate every field error into a single message so the
        // caller sees every problem in one response, not just the first.
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .reduce((a, b) -> a + "; " + b)
                .orElse("validation failed");
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", msg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraint(ConstraintViolationException ex) {
        String msg = ex.getConstraintViolations().stream()
                .map(this::formatViolation)
                .reduce((a, b) -> a + "; " + b)
                .orElse("constraint violation");
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", msg);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return body(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body is missing or malformed");
    }

    private String formatFieldError(FieldError fe) {
        return fe.getField() + ": " + (fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage());
    }

    private String formatViolation(ConstraintViolation<?> cv) {
        return cv.getPropertyPath() + ": " + cv.getMessage();
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(org.springframework.web.server.ResponseStatusException ex) {
        return body(HttpStatus.valueOf(ex.getStatusCode().value()), "UNAUTHORIZED", ex.getReason());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAll(Exception ex) {
        log.error("Unhandled exception", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status,
                                                            String code,
                                                            String message) {
        Map<String, Object> payload = Map.of(
                "error", code,
                "message", message == null ? "" : message,
                "status", status.value(),
                "timestamp", Instant.now().toString()
        );
        return ResponseEntity.status(status).body(payload);
    }
}