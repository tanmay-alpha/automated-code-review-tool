package com.automatedcodereviewtool.exception;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.Map;

/**
 * Maps exceptions to one JSON error shape:
 * {@code {"error": CODE, "message": text, "status": int, "timestamp": iso}}.
 *
 * <p><b>Never</b> leaks a stack trace, SQL, or framework message. Messages
 * are either written for the caller (validation, not-found) or fixed text;
 * the stack trace goes to the log only.</p>
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

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidRequest(InvalidRequestException ex) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage());
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

    /** A path or query parameter that cannot be converted, e.g. a non-UUID repo id. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Parameter '" + ex.getName() + "' is invalid");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParameter(MissingServletRequestParameterException ex) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Parameter '" + ex.getParameterName() + "' is required");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return body(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body is missing or malformed");
    }

    /** A write raced another one onto a unique/check constraint. The SQL error is logged, never returned. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getClass().getSimpleName());
        return body(HttpStatus.CONFLICT, "CONFLICT", "The request conflicts with the current state of the resource");
    }

    private String formatFieldError(FieldError fe) {
        return fe.getField() + ": " + (fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage());
    }

    private String formatViolation(ConstraintViolation<?> cv) {
        return cv.getPropertyPath() + ": " + cv.getMessage();
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(org.springframework.web.server.ResponseStatusException ex) {
        return body(ex.getStatusCode(), mapStatusCode(ex.getStatusCode().value()), ex.getReason());
    }

    /**
     * Map HTTP status codes to matching error-code strings.
     */
    private static String mapStatusCode(int status) {
        return switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 409 -> "CONFLICT";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 429 -> "RATE_LIMIT_EXCEEDED";
            case 500 -> "INTERNAL_ERROR";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> "ERROR";
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAll(Exception ex) {
        // Spring MVC's own client errors (unknown route, wrong method, wrong
        // content type, …) carry their status; they are not server faults.
        if (ex instanceof ErrorResponse errorResponse && errorResponse.getStatusCode().is4xxClientError()) {
            HttpStatusCode status = errorResponse.getStatusCode();
            HttpStatus known = HttpStatus.resolve(status.value());
            return body(status, mapStatusCode(status.value()),
                    known == null ? "Request rejected" : known.getReasonPhrase());
        }
        log.error("Unhandled exception", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatusCode status,
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
