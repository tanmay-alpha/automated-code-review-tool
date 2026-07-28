package com.automatedcodereviewtool.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Properties under the {@code app.webhook} prefix.
 *
 * <p>The callback URL must be a non-blank absolute URL. {@code @Validated}
 * causes Spring to enforce the constraints at bind time (application
 * startup), so a missing {@code WEBHOOK_CALLBACK_URL} env var produces
 * a clear failure rather than silently sending GitHub to the wrong
 * endpoint.</p>
 */
@ConfigurationProperties(prefix = "app.webhook")
@Validated
public class WebhookConfig {

    /**
     * Absolute URL GitHub will POST to when a {@code pull_request}
     * event fires. Used both at install-time and reflected in the
     * dashboard for debugging.
     */
    @NotBlank(message = "app.webhook.callback-url must not be blank")
    @Pattern(
            regexp = "^https?://[^\\s]+$",
            message = "app.webhook.callback-url must be an absolute http(s) URL")
    private String callbackUrl;

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public void setCallbackUrl(String callbackUrl) {
        this.callbackUrl = callbackUrl;
    }
}