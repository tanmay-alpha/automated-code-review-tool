package com.automatedcodereviewtool.dto.response;

import java.time.Instant;

/**
 * {@code GET /api/diagnostics}: dependency health plus the caller's queue
 * depth. {@code status} values are {@code up}, {@code degraded} or
 * {@code down}. Details are fixed, caller-safe text: never exception
 * messages, hostnames, credentials or stack traces.
 *
 * @param queue null when the database could not be queried
 */
public record DiagnosticsResponse(
        String status,
        Instant checkedAt,
        Components components,
        QueueStats queue
) {
    public record Components(
            Component database,
            Component redis,
            MlWorkerComponent mlWorker,
            Component github
    ) {
    }

    public record Component(String status, String detail) {
    }

    /** {@code engine} is what the worker reports ({@code model} or {@code fallback}); null when unreachable. */
    public record MlWorkerComponent(String status, String engine, String detail) {
    }

    /**
     * Webhook deliveries and dataset-capture outbox events for the caller's
     * repositories only.
     *
     * @param webhooksPending      received or processing
     * @param webhooksRetrying     failed, waiting for another attempt
     * @param webhooksDeadLetter   attempts exhausted or terminally rejected
     * @param oldestPendingSeconds age of the oldest pending or retrying delivery; null when none
     * @param outboxPending        pending or processing
     * @param outboxFailed         failed or dead-lettered
     */
    public record QueueStats(
            long webhooksPending,
            long webhooksRetrying,
            long webhooksDeadLetter,
            Long oldestPendingSeconds,
            long outboxPending,
            long outboxFailed
    ) {
    }
}
