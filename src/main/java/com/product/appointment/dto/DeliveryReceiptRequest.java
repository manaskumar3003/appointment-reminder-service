package com.product.appointment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A late delivery receipt from the provider. It carries our idempotency key (preferred) or the
 * provider's message id - at least one of the two.
 */
public record DeliveryReceiptRequest(
        @Size(max = 100) String idempotencyKey,
        @Size(max = 100) String providerMessageId,
        @NotNull Status status,
        @Size(max = 500) String error) {

    public enum Status { DELIVERED, UNDELIVERED }

    /** What the signature covers: key | message id | status, so a receipt can't be altered in flight. */
    public String signedPayload() {
        return nullToEmpty(idempotencyKey) + "|" + nullToEmpty(providerMessageId) + "|" + status;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
