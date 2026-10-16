package com.product.appointment.service;

import com.product.appointment.entity.NotificationLog;
import com.product.appointment.notification.NotificationSender;
import com.product.appointment.notification.ProviderUnavailableException;
import com.product.appointment.repository.NotificationLogRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * What happens AFTER a send: settling outcomes the provider never confirmed, and applying delivery
 * receipts that arrive late. {@link ReminderService} sends; this class finds out what really happened.
 */
@Service
public class DeliveryFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryFeedbackService.class);

    private final ReminderService reminderService;
    private final NotificationLogRepository notificationLogRepository;
    private final NotificationSender notificationSender;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meters;
    private final int batchSize;
    private final int leaseMinutes;
    private final int unknownDeadlineMinutes;

    public DeliveryFeedbackService(ReminderService reminderService,
                                   NotificationLogRepository notificationLogRepository,
                                   NotificationSender notificationSender,
                                   TransactionTemplate transactionTemplate,
                                   MeterRegistry meters,
                                   @Value("${reminders.batch-size:50}") int batchSize,
                                   @Value("${reminders.lease-minutes:5}") int leaseMinutes,
                                   @Value("${reminders.unknown-deadline-minutes:30}") int unknownDeadlineMinutes) {
        this.reminderService = reminderService;
        this.notificationLogRepository = notificationLogRepository;
        this.notificationSender = notificationSender;
        this.transactionTemplate = transactionTemplate;
        this.meters = meters;
        this.batchSize = batchSize;
        this.leaseMinutes = leaseMinutes;
        this.unknownDeadlineMinutes = unknownDeadlineMinutes;
    }

    /**
     * Settles unconfirmed sends that no retry will revisit, by probing the provider: it has the message
     * → SENT; it definitely doesn't → FAILED (nothing went out); it can't say → keep asking, and past
     * the deadline mark IN_DOUBT and alert a human.
     */
    public int reconcileUnconfirmed() {
        int settled = 0;
        OffsetDateTime deadline = OffsetDateTime.now().minusMinutes(unknownDeadlineMinutes);
        for (NotificationLog entry : notificationLogRepository.findUnconfirmed(leaseMinutes, batchSize)) {
            Long reminderId = entry.getReminderId();
            try {
                Optional<String> providerMessageId =
                        notificationSender.findSent(NotificationLog.idempotencyKey(reminderId));
                transactionTemplate.executeWithoutResult(status -> {
                    if (providerMessageId.isPresent()) {
                        count("probe_found");
                        log.info("Reminder {} was delivered before it was confirmed; marking SENT", reminderId);
                        reminderService.confirm(reminderId, providerMessageId.get());
                    } else {
                        count("probe_absent");
                        log.info("Reminder {} never reached the provider; marking it not delivered", reminderId);
                        notificationLogRepository.markNotDelivered(reminderId, "provider never received it");
                    }
                });
                settled++;
            } catch (ProviderUnavailableException e) {
                if (entry.getCreatedAt().isBefore(deadline)) {
                    transactionTemplate.executeWithoutResult(status -> notificationLogRepository.markInDoubt(
                            reminderId, "still unknown after " + unknownDeadlineMinutes + "m: " + e.getMessage()));
                    count("in_doubt");
                    log.error("ALERT reminder {} IN_DOUBT: provider still can't say whether it was delivered "
                            + "after {}m - needs a human (or a fallback channel)", reminderId, unknownDeadlineMinutes);
                    settled++;
                } else {
                    log.info("Reminder {}: provider can't confirm yet; asking again next poll", reminderId);
                }
            } catch (Exception e) {
                log.warn("Could not reconcile reminder {}; will ask again next poll", reminderId, e);
            }
        }
        return settled;
    }

    /**
     * A delivery receipt from the provider's webhook - possibly late, duplicated or out of order.
     * Every write is conditional, so applying the same receipt twice changes nothing, and a late
     * "undelivered" can never overwrite a confirmed delivery.
     *
     * @return false if the receipt matches no reminder we know
     */
    @Transactional
    public boolean applyReceipt(String idempotencyKey, String providerMessageId, boolean delivered, String error) {
        Optional<Long> reminderId = NotificationLog.reminderIdOf(idempotencyKey)
                .or(() -> Optional.ofNullable(providerMessageId)
                        .flatMap(notificationLogRepository::findByProviderMessageId)
                        .map(NotificationLog::getReminderId));
        if (reminderId.isEmpty() || !notificationLogRepository.existsByReminderId(reminderId.get())) {
            log.warn("Receipt for an unknown message (key={}, providerMessageId={}); ignoring",
                    idempotencyKey, providerMessageId);
            return false;
        }
        Long id = reminderId.get();
        if (delivered) {
            count("receipt_delivered");
            if (notificationLogRepository.markDelivered(id, providerMessageId) > 0) {
                log.info("Receipt: reminder {} DELIVERED", id);
            }
            reminderService.markReminderSent(id);
        } else {
            count("receipt_undelivered");
            if (notificationLogRepository.markUndelivered(id, error == null ? "undelivered" : error) > 0) {
                log.warn("Receipt: reminder {} could NOT be delivered ({})", id, error);
            }
        }
        return true;
    }

    private void count(String outcome) {
        meters.counter("reminders.outcome", "outcome", outcome).increment();
    }
}
