package com.codelens.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Idempotency table — a row per {@code X-GitHub-Delivery} id we've
 * already processed. Lets us safely retry delivery without double-posting
 * a CodeLens comment.
 *
 * <p>Old rows can be GC'd by a scheduled job (the plan suggests &gt;24h).</p>
 */
@Entity
@Table(name = "processed_webhooks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedWebhook {

    /** GitHub's {@code X-GitHub-Delivery} header (UUID-like). */
    @Id
    @Column(name = "delivery_id", length = 100)
    private String deliveryId;

    @CreationTimestamp
    @Column(name = "processed_at", updatable = false)
    private Instant processedAt;
}
