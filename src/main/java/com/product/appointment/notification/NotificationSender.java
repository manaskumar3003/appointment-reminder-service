package com.product.appointment.notification;

import com.product.appointment.entity.Reminder;

import java.util.Optional;

public interface NotificationSender {

    /**
     * Hands the reminder to the provider. Never relied on to deduplicate: the service probes with
     * {@link #findSent} before any resend. A timeout or 5xx must come back as {@link SendResult.Unknown}
     * (or be thrown), never as Rejected - only a definite refusal is Rejected.
     */
    SendResult send(Reminder reminder, String idempotencyKey);

    /**
     * Probe: the provider's message id for this key if it has the message, empty if it definitely
     * never received it. Throws {@link ProviderUnavailableException} when it can't say.
     */
    Optional<String> findSent(String idempotencyKey);
}
