package com.product.appointment.notification;

import com.product.appointment.entity.Reminder;

import java.util.Optional;

public interface NotificationSender {

    /**
     * Hands the reminder to the provider and returns the provider's message id. Calling again with
     * the same idempotency key must not deliver twice: the provider returns the original id instead.
     */
    String send(Reminder reminder, String idempotencyKey);

    /** The provider's message id for this key, or empty if the provider never received it. */
    Optional<String> findSent(String idempotencyKey);
}
