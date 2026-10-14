package com.product.appointment.notification;

import com.product.appointment.entity.Reminder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logs the payload and sends nothing, but behaves like a provider that honours idempotency keys:
 * a repeated key returns the original message id without "sending" again.
 *
 * The keys live in this JVM, so they die with it. A real provider keeps them on its side, which is
 * what makes a retry after our crash safe; the stub only shows the contract.
 */
@Component
public class StubNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(StubNotificationSender.class);

    private final Map<String, String> sentByKey = new ConcurrentHashMap<>();

    @Override
    public String send(Reminder reminder, String idempotencyKey) {
        String messageId = "stub-" + UUID.randomUUID();
        String original = sentByKey.putIfAbsent(idempotencyKey, messageId);
        if (original != null) {
            log.info("Duplicate send for {} ignored; returning original message {}", idempotencyKey, original);
            return original;
        }
        log.info("NOTIFICATION reminderId={} appointmentId={} type={} contact={} appointmentAt={} messageId={}",
                reminder.getId(),
                reminder.getAppointment().getId(),
                reminder.getReminderType(),
                mask(reminder.getAppointment().getCustomerContact()),
                reminder.getAppointment().getScheduledAt(),
                messageId);
        return messageId;
    }

    @Override
    public Optional<String> findSent(String idempotencyKey) {
        return Optional.ofNullable(sentByKey.get(idempotencyKey));
    }

    /** Enough to tell two customers apart in a log, not enough to be a leak. */
    private static String mask(String contact) {
        if (contact == null || contact.length() < 6) {
            return "****";
        }
        return contact.substring(0, 3) + "****" + contact.substring(contact.length() - 2);
    }
}
