package com.product.appointment.entity;

public enum DeliveryStatus {
    /** Intent recorded; the provider may or may not have the message yet. */
    SENDING,
    /** The provider accepted the message. */
    SENT,
    /** A delivery receipt confirmed it reached the handset (may arrive late, or never). */
    DELIVERED,
    /** The provider never answered (timeout, 5xx): it may have gone out. Never blindly resent. */
    UNKNOWN,
    /** Definitely not delivered: the provider refused it, or confirmed it never received it. */
    FAILED,
    /** Still UNKNOWN past the deadline and the provider can't say: needs a human. */
    IN_DOUBT;

    /** The customer has (or may have) this message, so it must never be sent again. */
    public boolean deliveredOrMaybe() {
        return this == SENT || this == DELIVERED || this == IN_DOUBT;
    }
}
