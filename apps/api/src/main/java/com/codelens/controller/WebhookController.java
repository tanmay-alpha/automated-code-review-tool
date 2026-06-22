package com.codelens.controller;

import com.codelens.entity.ProcessedWebhook;
import com.codelens.repository.ProcessedWebhookRepository;
import com.codelens.service.WebhookService;
import com.codelens.webhook.GitHubWebhookEvent;
import com.codelens.webhook.HmacVerificationException;
import com.codelens.webhook.HmacVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * Endpoint that receives GitHub {@code pull_request} webhooks.
 *
 * <p>All responses are 200 OK unless the signature is forged — GitHub
 * marks slow / non-2xx responses as failures and will redeliver. So the
 * controller is intentionally permissive: pings, pushes, and
 * uninteresting actions all return 200 with no processing.</p>
 */
@RestController
@RequestMapping("/api/webhook")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private static final String EVENT_PULL_REQUEST = "pull_request";

    private static final Set<String> PROCESSED_ACTIONS =
            Set.of("opened", "synchronize", "reopened");

    private final HmacVerifier hmacVerifier;
    private final WebhookService webhookService;
    private final ProcessedWebhookRepository processedWebhookRepository;
    private final ObjectMapper objectMapper;

    public WebhookController(HmacVerifier hmacVerifier,
                            WebhookService webhookService,
                            ProcessedWebhookRepository processedWebhookRepository,
                            ObjectMapper objectMapper) {
        this.hmacVerifier = hmacVerifier;
        this.webhookService = webhookService;
        this.processedWebhookRepository = processedWebhookRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/github")
    public ResponseEntity<Void> handle(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestBody String payload) {

        if (deliveryId == null || deliveryId.isBlank()) {
            return ResponseEntity.ok().build();
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

        // 3. HMAC: must match the secret we stored when installing the hook.
        //    An "unknown repo" (no secret on file) is a 200 — not our hook.
        try {
            if (!hmacVerifier.verify(payload, signature, body.repository().id().toString())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
        } catch (HmacVerificationException ex) {
            log.debug("Ignoring webhook for unknown repo: {}", ex.getMessage());
            return ResponseEntity.ok().build();
        }

        // 4. Idempotency: skip if we've already processed this delivery.
        if (processedWebhookRepository.existsById(deliveryId)) {
            return ResponseEntity.ok().build();
        }

        // 5. Action filter: only opened / synchronize / reopened produce a review.
        if (!PROCESSED_ACTIONS.contains(body.action())) {
            // Still record the delivery so we don't reprocess on retry.
            processedWebhookRepository.save(ProcessedWebhook.builder()
                    .deliveryId(deliveryId).build());
            return ResponseEntity.ok().build();
        }

        // 6. Record the delivery up front — even if the async work fails,
        //    we don't want GitHub to redeliver.
        try {
            processedWebhookRepository.save(ProcessedWebhook.builder()
                    .deliveryId(deliveryId).build());
        } catch (Exception ex) {
            log.warn("Failed to record delivery {}: {}", deliveryId, ex.getMessage());
            return ResponseEntity.ok().build();
        }

        // 7. Hand off to the async worker. Returns immediately.
        webhookService.processAsync(body, body.repository().id().toString());

        return ResponseEntity.ok().build();
    }
}