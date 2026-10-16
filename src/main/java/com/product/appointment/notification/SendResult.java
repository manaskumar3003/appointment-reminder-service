package com.product.appointment.notification;

/**
 * What one hand-off to the provider produced. Sealed, so every caller has to handle all three.
 * The split that matters: Rejected means definitely NOT sent (safe to try again), Unknown means
 * it may have gone out (never blindly resent).
 */
public sealed interface SendResult {

    /** The provider took the message. */
    record Accepted(String providerMessageId) implements SendResult {
    }

    /** The provider definitely refused it. Permanent (bad number, opted out) or worth retrying (rate limit). */
    record Rejected(String reason, boolean permanent) implements SendResult {
    }

    /** No usable answer: timeout, connection reset, 5xx. The message may or may not have gone out. */
    record Unknown(String reason) implements SendResult {
    }
}
