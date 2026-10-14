package com.product.appointment.service;

import com.product.appointment.entity.Appointment;
import com.product.appointment.entity.AppointmentStatus;
import com.product.appointment.entity.DeliveryStatus;
import com.product.appointment.entity.NotificationLog;
import com.product.appointment.entity.Reminder;
import com.product.appointment.entity.ReminderStatus;
import com.product.appointment.notification.NotificationSender;
import com.product.appointment.repository.NotificationLogRepository;
import com.product.appointment.repository.ReminderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    private final ReminderRepository reminderRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final NotificationSender notificationSender;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final int maxAttempts;
    private final int leaseMinutes;
    private final int retryDelayMinutes;

    public ReminderService(ReminderRepository reminderRepository,
                           NotificationLogRepository notificationLogRepository,
                           NotificationSender notificationSender,
                           TransactionTemplate transactionTemplate,
                           @Value("${reminders.batch-size:500}") int batchSize,
                           @Value("${reminders.max-attempts:3}") int maxAttempts,
                           @Value("${reminders.lease-minutes:5}") int leaseMinutes,
                           @Value("${reminders.retry-delay-minutes:5}") int retryDelayMinutes) {
        this.reminderRepository = reminderRepository;
        this.notificationLogRepository = notificationLogRepository;
        this.notificationSender = notificationSender;
        this.transactionTemplate = transactionTemplate;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.leaseMinutes = leaseMinutes;
        this.retryDelayMinutes = retryDelayMinutes;
    }

    /** Commits the claim before any sending: an open claim would block the log insert on its own lock. */
    @Transactional
    public List<Long> claimBatch() {
        return reminderRepository.claimDueReminders(batchSize);
    }

    /**
     * Three steps, each committed: record the intent, call the provider, record its answer. A crash
     * between the call and the answer leaves a SENDING row instead of no trace, and the retry sends
     * under the same idempotency key, so the provider returns the original message.
     *
     * The provider call runs outside any transaction - a slow provider would otherwise hold a pooled
     * connection and the reminder's row lock for the length of every call.
     */
    public void sendClaimed(Long reminderId) {
        Delivery delivery = transactionTemplate.execute(status -> recordIntent(reminderId));
        if (delivery == null) {
            return;
        }

        String providerMessageId = notificationSender.send(delivery.reminder(), delivery.idempotencyKey());

        transactionTemplate.executeWithoutResult(status -> confirm(reminderId, providerMessageId));
    }

    private record Delivery(Reminder reminder, String idempotencyKey) {
    }

    /** Null when there is nothing to send: the reminder went stale, or an earlier send was confirmed. */
    private Delivery recordIntent(Long reminderId) {

        Reminder reminder = reminderRepository.findByIdWithAppointment(reminderId).orElseThrow();

        String stale = staleReason(reminder.getAppointment());
        if (stale != null) {
            reminder.setStatus(ReminderStatus.SKIPPED);
            reminder.setProcessingStartedAt(null);
            log.info("Skipping reminder {}: {}", reminderId, stale);
            return null;
        }

        Optional<NotificationLog> earlier = notificationLogRepository.findByReminderId(reminderId);
        if (earlier.isPresent() && earlier.get().getStatus() == DeliveryStatus.SENT) {
            log.info("Reminder {} already notified; not re-sending", reminderId);
            markSent(reminder);
            return null;
        }

        if (earlier.isPresent()) {
            log.info("Reminder {} has an unconfirmed send; retrying under the same idempotency key", reminderId);
        } else {
            NotificationLog intent = new NotificationLog();
            intent.setReminderId(reminderId);
            intent.setStatus(DeliveryStatus.SENDING);
            // UNIQUE(reminder_id) refuses a second intent; the lookup above is only a fast path.
            notificationLogRepository.saveAndFlush(intent);
        }
        return new Delivery(reminder, NotificationLog.idempotencyKey(reminderId));
    }

    /** The provider took it, so the reminder is SENT - even if it was parked meanwhile. */
    private void confirm(Long reminderId, String providerMessageId) {
        notificationLogRepository.markSent(reminderId, providerMessageId);
        markSent(reminderRepository.findById(reminderId).orElseThrow());
    }

    private static void markSent(Reminder reminder) {
        reminder.setStatus(ReminderStatus.SENT);
        reminder.setSentAt(OffsetDateTime.now());
        reminder.setProcessingStartedAt(null);
    }

    /** A claimed reminder can stop being worth sending: cancelled, or the appointment already ran. */
    private String staleReason(Appointment appointment) {
        if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
            return "appointment is " + appointment.getStatus();
        }
        if (appointment.getScheduledAt().isBefore(OffsetDateTime.now())) {
            return "appointment already started at " + appointment.getScheduledAt();
        }
        return null;
    }

    /**
     * Fresh transaction - the failed step's transaction is already rolled back. Any SENDING row
     * stays, so the retry reuses its idempotency key.
     *
     * One conditional UPDATE rather than read-then-write: a worker that lost a lease race fails on
     * the notification_log constraint after the winner committed SENT, and must not undo that.
     */
    @Transactional
    public void recordFailure(Long reminderId, Exception cause) {
        // Hold it back before retrying: the failures worth retrying are rate limits and
        // outages, and hammering the next poll would spend every attempt inside a minute.
        int released = reminderRepository.releaseAfterFailure(reminderId, maxAttempts, retryDelayMinutes);
        if (released == 0) {
            log.warn("Reminder {} failed but is no longer PROCESSING; leaving its state alone",
                    reminderId, cause);
            return;
        }
        Reminder reminder = reminderRepository.findById(reminderId).orElseThrow();
        boolean exhausted = reminder.getStatus() == ReminderStatus.FAILED;
        log.warn("Reminder {} failed on attempt {}/{}{}", reminderId,
                reminder.getAttemptCount(), maxAttempts,
                exhausted ? " - giving up" : " - retrying in " + retryDelayMinutes + "m", cause);
    }

    @Transactional
    public int recoverStaleReminders() {
        int recovered = reminderRepository.recoverStaleReminders(leaseMinutes, maxAttempts);
        if (recovered > 0) {
            log.info("Recovered {} reminders stranded in PROCESSING", recovered);
        }
        return recovered;
    }

    /**
     * Settles unconfirmed sends that no retry will revisit, by asking the provider. It has the
     * message: it was delivered, so record SENT. It doesn't: nothing went out, so clear the intent.
     * A provider that can't answer is asked again on the next poll.
     */
    public int reconcileUnconfirmed() {
        int settled = 0;
        for (NotificationLog entry : notificationLogRepository.findUnconfirmed(leaseMinutes, batchSize)) {
            Long reminderId = entry.getReminderId();
            try {
                Optional<String> providerMessageId =
                        notificationSender.findSent(NotificationLog.idempotencyKey(reminderId));
                transactionTemplate.executeWithoutResult(status -> {
                    if (providerMessageId.isPresent()) {
                        log.info("Reminder {} was delivered before it was confirmed; marking SENT", reminderId);
                        confirm(reminderId, providerMessageId.get());
                    } else {
                        log.info("Reminder {} never reached the provider; clearing its unconfirmed send", reminderId);
                        notificationLogRepository.deleteUnconfirmed(reminderId);
                    }
                });
                settled++;
            } catch (Exception e) {
                log.warn("Could not reconcile reminder {}; will ask again next poll", reminderId, e);
            }
        }
        return settled;
    }
}
