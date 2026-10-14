package com.product.appointment.entity;

public enum DeliveryStatus {
    /** Intent recorded; the provider may or may not have the message yet. */
    SENDING,
    /** The provider accepted the message. */
    SENT
}
