package com.automatedcodereviewtool.controller;

import com.automatedcodereviewtool.logging.SecurityEventLogger;
import com.automatedcodereviewtool.security.RequestSizeLimitFilter;
import com.automatedcodereviewtool.service.WebhookService;
import com.automatedcodereviewtool.webhook.GitHubWebhookEvent;
import com.automatedcodereviewtool.webhook.HmacVerificationException;
import com.automatedcodereviewtool.webhook.HmacVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Set;

/**
 * Endpoint that receives GitHub {@code pull_request} webhooks.
 *
 * <p>Forged signatures get 400, durability failures 503 so GitHub can
 * redeliver. Pings, other events, other actions and repos we don't know
 * (or that were disconnected) get 200 and are ignored.</p>
 */
@RestController
@RequestMapping("/api/webhook")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private static final String EVENT_PULL_REQUEST = "pull_request";
    private static final int MAX_PAYLOAD_BYTES = 1_048_576;

    private static final Set<String> PROCESSED_ACTIONS =
            Set.of("opened", "synchronize", "reopened");

    private final HmacVerifier hmacVerifier;
    private final WebhookService webhookService;
    private final ObjectMapper objectMapper;
    private final SecurityEventLogger securityEventLogger;

    public WebhookController(HmacVerifier hmacVerifier,
                             WebhookService webhookService,
                             ObjectMapper objectMapper,
                             SecurityEventLogger securityEventLogger) {
        this.hmacVerifier = hmacVerifier;
        this.webhookService = webhookService;
        this.objectMapper = objectMapper;
        this.securityEventLogger = securityEventLogger;
    }

    @PostMapping("/github")
    public ResponseEntity<Void> handle(
            HttpServletRequest request,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId) {

        // Read at most one byte past the limit, so a chunked body (no
        // Content-Length for RequestSizeLimitFilter to reject) is never
        // buffered in full. The signature is computed by GitHub over these
        // exact bytes, so they are verified and parsed as-is — never decoded
        // and re-encoded.
        byte[] payload;
        try {
            payload = request.getInputStream().readNBytes(MAX_PAYLOAD_BYTES + 1);
        } catch (RequestSizeLimitFilter.BodyTooLargeException ex) {
            log.warn("Webhook payload rejected: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        } catch (IOException ex) {
            log.warn("Failed to read webhook payload: {}", ex.getMessage());
            return ResponseEntity.badRequest().build();
        }
        if (payload.length > MAX_PAYLOAD_BYTES) {
            log.warn("Webhook payload exceeds {} bytes", MAX_PAYLOAD_BYTES);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        if (payload.length == 0) {
            return ResponseEntity.badRequest().build();
        }

        if (deliveryId == null || deliveryId.isBlank()) {
            // A blank delivery-id means we can't dedupe — treat as
            // a malformed request rather than silently processing.
            return ResponseEntity.badRequest().build();
        }

        // 1. Event filter: only process pull_request.
        if (!EVENT_PULL_REQUEST.equals(event)) {
            return ResponseEntity.ok().build();
        }

        // 2. Parse just enough to find the repo id and PR action.
        GitHubWebhookEvent body;
        try {
            body = objectMapper.readValue(payload, GitHubWebhookEvent.class);
        } catch (Exception ex) {
            log.warn("Failed to parse webhook payload: {}", ex.getMessage());
            return ResponseEntity.ok().build();
        }
        if (body.repository() == null || body.repository().id() == null) {
            return ResponseEntity.ok().build();
        }

        String ip = request.getRemoteAddr();
        if (ip == null || ip.isBlank()) {
            ip = "unknown";
        }

        // 3. HMAC: must match the secret we stored when installing the hook.
        //    An "unknown repo" (no secret on file) is a 200 — not our hook.
        String repoId = body.repository().id().toString();
        try {
            boolean valid = hmacVerifier.verify(payload, signature, repoId);
            securityEventLogger.logWebhookSecurityEvent(repoId, valid, ip, body.action());
            if (!valid) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
            }
        } catch (HmacVerificationException ex) {
            log.debug("Ignoring webhook for unknown repo: {}", ex.getMessage());
            return ResponseEntity.ok().build();
        }

        // Only opened / synchronize / reopened produce a review.
        if (!PROCESSED_ACTIONS.contains(body.action())) {
            return ResponseEntity.ok().build();
        }

        // Commit durable delivery state before acknowledging GitHub.
        boolean accepted;
        try {
            accepted = webhookService.receiveDelivery(
                    deliveryId, body, body.repository().id());
        } catch (Exception ex) {
            log.error("Failed to durably accept delivery {}: {}", deliveryId, ex.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        if (!accepted) {
            return ResponseEntity.ok().build();
        }

        webhookService.processAsync(deliveryId);

        return ResponseEntity.ok().build();
    }
}
