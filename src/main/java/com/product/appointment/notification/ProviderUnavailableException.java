package com.product.appointment.notification;

/** The provider couldn't answer a lookup, so we can't tell whether a message went out. */
public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(String message) {
        super(message);
    }
}
