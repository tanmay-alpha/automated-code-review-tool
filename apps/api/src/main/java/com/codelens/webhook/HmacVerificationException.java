package com.codelens.webhook;

/**
 * Thrown when an HMAC verification cannot be performed — for example,
 * because we have no webhook secret on file for the repo that sent the
 * payload. Distinct from a simple "signature did not match" return value
 * so callers can choose the right HTTP status.
 */
public class HmacVerificationException extends RuntimeException {
    public HmacVerificationException(String message) {
        super(message);
    }
}
