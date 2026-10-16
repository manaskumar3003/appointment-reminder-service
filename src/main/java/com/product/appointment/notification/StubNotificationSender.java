package com.product.appointment.notification;

import com.product.appointment.entity.Reminder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logs the payload and sends nothing, but behaves like an UNRELIABLE provider so every path can be
 * demoed. The contact's last four digits pick the behaviour:
 *
 *   ...0000  the message goes out but the reply is lost      → Unknown; a probe finds it
 *   ...7777  the provider is down: nothing sent, can't answer → Unknown; probes fail → IN_DOUBT
 *   ...8888  rate-limited on the first attempt, then accepted → Rejected (retryable), then Accepted
 *   ...9999  invalid number                                   → Rejected (permanent)
 *   anything else                                             → Accepted
 *
 * It remembers keys it delivered, so it can answer probes. The memory dies with the JVM; a real
 * provider keeps it on its side.
 */
@Component
public class StubNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(StubNotificationSender.class);

    private final Map<String, String> sentByKey = new ConcurrentHashMap<>();
    private final Set<String> unreachable = ConcurrentHashMap.newKeySet();
    private final Set<String> rateLimitedOnce = ConcurrentHashMap.newKeySet();

    @Override
    public SendResult send(Reminder reminder, String idempotencyKey) {
        String contact = reminder.getAppointment().getCustomerContact();

        if (contact.endsWith("9999")) {
            log.info("PROVIDER REJECTED {} permanently: invalid number {}", idempotencyKey, mask(contact));
            return new SendResult.Rejected("invalid number", true);
        }
        if (contact.endsWith("8888") && rateLimitedOnce.add(idempotencyKey)) {
            log.info("PROVIDER REJECTED {}: rate limited, try later", idempotencyKey);
            return new SendResult.Rejected("rate limited (429)", false);
        }
        if (contact.endsWith("7777")) {
            unreachable.add(idempotencyKey);
            log.info("PROVIDER DOWN for {}: no answer, nothing sent", idempotencyKey);
            return new SendResult.Unknown("timeout: provider unreachable");
        }

        String messageId = "stub-" + UUID.randomUUID();
        String original = sentByKey.putIfAbsent(idempotencyKey, messageId);
        if (original != null) {
            log.info("Duplicate send for {} ignored; returning original message {}", idempotencyKey, original);
            return new SendResult.Accepted(original);
        }
        log.info("NOTIFICATION reminderId={} appointmentId={} type={} contact={} appointmentAt={} messageId={}",
                reminder.getId(),
                reminder.getAppointment().getId(),
                reminder.getReminderType(),
                mask(contact),
                reminder.getAppointment().getScheduledAt(),
                messageId);

        if (contact.endsWith("0000")) {
            log.info("PROVIDER REPLY LOST for {}: the message went out, the caller hears nothing", idempotencyKey);
            return new SendResult.Unknown("timeout: no reply");
        }
        return new SendResult.Accepted(messageId);
    }

    @Override
    public Optional<String> findSent(String idempotencyKey) {
        if (unreachable.contains(idempotencyKey)) {
            throw new ProviderUnavailableException("provider unreachable, can't look up " + idempotencyKey);
        }
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
