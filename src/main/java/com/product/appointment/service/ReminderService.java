package com.product.appointment.service;

import com.product.appointment.entity.Appointment;
import com.product.appointment.entity.AppointmentStatus;
import com.product.appointment.entity.DeliveryStatus;
import com.product.appointment.entity.NotificationLog;
import com.product.appointment.entity.Reminder;
import com.product.appointment.entity.ReminderStatus;
import com.product.appointment.notification.NotificationSender;
import com.product.appointment.notification.SendResult;
import com.product.appointment.repository.NotificationLogRepository;
import com.product.appointment.repository.ReminderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sends reminders through a provider that can't be trusted to answer. Exactly-once is impossible, and
 * the brief says never twice, so: record the intent before calling, never resend blindly, and before
 * any resend PROBE the provider - it has the message → SENT; it definitely doesn't → safe to send;
 * it can't say → stay UNKNOWN. What happens after the send (reconciling, the IN_DOUBT deadline, late
 * receipts) lives in {@link DeliveryFeedbackService}.
 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    /** A reminder this worker claimed, with the fencing token that proves it still owns it. */
    public record Claim(Long reminderId, String token) {
    }

    private record Delivery(Reminder reminder, String idempotencyKey) {
    }

    private final ReminderRepository reminderRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final NotificationSender notificationSender;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meters;
    private final int batchSize;
    private final int maxAttempts;
    private final int leaseMinutes;
    private final int retryDelayMinutes;

    public ReminderService(ReminderRepository reminderRepository,
                           NotificationLogRepository notificationLogRepository,
                           NotificationSender notificationSender,
                           TransactionTemplate transactionTemplate,
                           MeterRegistry meters,
                           @Value("${reminders.batch-size:50}") int batchSize,
                           @Value("${reminders.max-attempts:3}") int maxAttempts,
                           @Value("${reminders.lease-minutes:5}") int leaseMinutes,
                           @Value("${reminders.retry-delay-minutes:5}") int retryDelayMinutes) {
        this.reminderRepository = reminderRepository;
        this.notificationLogRepository = notificationLogRepository;
        this.notificationSender = notificationSender;
        this.transactionTemplate = transactionTemplate;
        this.meters = meters;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.leaseMinutes = leaseMinutes;
        this.retryDelayMinutes = retryDelayMinutes;
    }

    // ---------------------------------------------------------------- 1. claim

    /**
     * Commits the claim before any sending: an open claim would block the log insert on its own lock.
     * The batch shares one fresh fencing token; a reclaim later gets a new one.
     */
    @Transactional
    public List<Claim> claimBatch() {
        String token = UUID.randomUUID().toString();
        return reminderRepository.claimDueReminders(batchSize, token).stream()
                .map(id -> new Claim(id, token))
                .toList();
    }

    // ---------------------------------------------------------------- 2-5. send

    /**
     * Probe if an earlier attempt is unclear, record the intent, call the provider once with no
     * transaction open, then record what came back. Each database step is its own short transaction.
     */
    public void sendClaimed(Claim claim) {
        Long reminderId = claim.reminderId();
        String key = NotificationLog.idempotencyKey(reminderId);

        // Probe before retry: an earlier attempt never got a clear answer, so ask before resending.
        Optional<NotificationLog> earlier = notificationLogRepository.findByReminderId(reminderId);
        if (earlier.isPresent() && isUnconfirmed(earlier.get().getStatus())) {
            Optional<String> alreadySent;
            try {
                alreadySent = notificationSender.findSent(key);
            } catch (RuntimeException e) {
                count("probe_unavailable");
                log.warn("Reminder {}: can't tell whether the last attempt went out ({}); not resending",
                        reminderId, e.getMessage());
                transactionTemplate.executeWithoutResult(status -> {
                    notificationLogRepository.markUnknown(reminderId, "probe failed: " + e.getMessage());
                    release(claim, "provider can't confirm an earlier attempt");
                });
                return;
            }
            if (alreadySent.isPresent()) {
                count("probe_found");
                log.info("Reminder {}: the provider already has it ({}); marking SENT, not resending",
                        reminderId, alreadySent.get());
                transactionTemplate.executeWithoutResult(status -> confirm(reminderId, alreadySent.get()));
                return;
            }
            count("probe_absent");
            log.info("Reminder {}: the provider never received the last attempt; safe to send", reminderId);
        }

        Delivery delivery = transactionTemplate.execute(status -> recordIntent(claim));
        if (delivery == null) {
            return;
        }

        SendResult result;
        try {
            result = notificationSender.send(delivery.reminder(), delivery.idempotencyKey());
        } catch (RuntimeException e) {
            // A thrown call (timeout, reset) might have reached the provider: that's Unknown, not Rejected.
            result = new SendResult.Unknown(e.toString());
        }

        SendResult outcome = result;
        transactionTemplate.executeWithoutResult(status -> applyOutcome(claim, outcome));
    }

    /** Null when there is nothing to send: stale, already delivered, or this claim lost ownership. */
    private Delivery recordIntent(Claim claim) {
        Long reminderId = claim.reminderId();
        Reminder reminder = reminderRepository.findByIdWithAppointment(reminderId).orElseThrow();

        if (reminder.getStatus() != ReminderStatus.PROCESSING || !claim.token().equals(reminder.getClaimToken())) {
            log.info("Reminder {} is no longer held by this claim; leaving it alone", reminderId);
            return null;
        }

        String stale = staleReason(reminder.getAppointment());
        if (stale != null) {
            reminder.setStatus(ReminderStatus.SKIPPED);
            reminder.setProcessingStartedAt(null);
            reminder.setClaimToken(null);
            log.info("Skipping reminder {}: {}", reminderId, stale);
            return null;
        }

        Optional<NotificationLog> earlier = notificationLogRepository.findByReminderId(reminderId);
        if (earlier.isPresent() && earlier.get().getStatus().deliveredOrMaybe()) {
            log.info("Reminder {} already notified ({}); not re-sending", reminderId, earlier.get().getStatus());
            markSent(reminder);
            return null;
        }

        if (earlier.isPresent()) {
            // Known not delivered (rejected, or the probe just confirmed it never arrived): reuse the row.
            notificationLogRepository.resetToSending(reminderId);
        } else {
            NotificationLog intent = new NotificationLog();
            intent.setReminderId(reminderId);
            intent.setStatus(DeliveryStatus.SENDING);
            // UNIQUE(reminder_id) refuses a second intent; the lookup above is only a fast path.
            notificationLogRepository.saveAndFlush(intent);
        }
        return new Delivery(reminder, NotificationLog.idempotencyKey(reminderId));
    }

    /** Three outcomes, three different rules. Only a definite refusal frees the reminder for a plain resend. */
    private void applyOutcome(Claim claim, SendResult result) {
        Long reminderId = claim.reminderId();
        switch (result) {
            case SendResult.Accepted accepted -> {
                count("sent");
                confirm(reminderId, accepted.providerMessageId());
            }
            case SendResult.Rejected rejected when rejected.permanent() -> {
                count("rejected_permanent");
                notificationLogRepository.markNotDelivered(reminderId, rejected.reason());
                if (reminderRepository.failPermanently(reminderId, claim.token()) > 0) {
                    log.warn("Reminder {} FAILED: provider refused it for good ({})", reminderId, rejected.reason());
                }
            }
            case SendResult.Rejected rejected -> {
                count("rejected");
                notificationLogRepository.markNotDelivered(reminderId, rejected.reason());
                release(claim, "rejected: " + rejected.reason());
            }
            case SendResult.Unknown unknown -> {
                count("unknown");
                notificationLogRepository.markUnknown(reminderId, unknown.reason());
                log.warn("Reminder {}: no clear answer from the provider ({}); it may have gone out - "
                        + "the next attempt probes before resending", reminderId, unknown.reason());
                release(claim, "outcome unknown: " + unknown.reason());
            }
        }
    }

    /**
     * The provider took it, so the reminder is SENT - even if it was parked meanwhile. Also used by
     * {@link DeliveryFeedbackService} when a probe or receipt confirms a send after the fact.
     */
    @Transactional
    public void confirm(Long reminderId, String providerMessageId) {
        notificationLogRepository.markSent(reminderId, providerMessageId);
        markSent(reminderRepository.findById(reminderId).orElseThrow());
    }

    /** A delivery receipt arrived: whatever the reminder was doing, it reached the customer. */
    @Transactional
    public void markReminderSent(Long reminderId) {
        markSent(reminderRepository.findById(reminderId).orElseThrow());
    }

    private static void markSent(Reminder reminder) {
        reminder.setStatus(ReminderStatus.SENT);
        if (reminder.getSentAt() == null) {
            reminder.setSentAt(OffsetDateTime.now());
        }
        reminder.setProcessingStartedAt(null);
        reminder.setClaimToken(null);
    }

    /** Back to PENDING after the retry delay, or FAILED once out of attempts - only while we still own it. */
    private void release(Claim claim, String reason) {
        int released = reminderRepository.releaseAfterFailure(claim.reminderId(), claim.token(),
                maxAttempts, retryDelayMinutes);
        if (released == 0) {
            log.warn("Reminder {} ({}) is no longer held by this claim; leaving its state alone",
                    claim.reminderId(), reason);
            return;
        }
        Reminder reminder = reminderRepository.findById(claim.reminderId()).orElseThrow();
        boolean exhausted = reminder.getStatus() == ReminderStatus.FAILED;
        log.warn("Reminder {} attempt {}/{} - {}{}", claim.reminderId(), reminder.getAttemptCount(), maxAttempts,
                reason, exhausted ? " - giving up" : " - retrying in " + retryDelayMinutes + "m");
    }

    private static boolean isUnconfirmed(DeliveryStatus status) {
        return status == DeliveryStatus.SENDING || status == DeliveryStatus.UNKNOWN;
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

    // ---------------------------------------------------------------- failures and recovery

    /**
     * A step threw (database error, lost race on the unique constraint). Fresh transaction - the failed
     * step's is already rolled back. Fenced, so a worker that lost its claim can't reset a newer one.
     * Any SENDING row stays, so the next attempt probes before resending.
     */
    @Transactional
    public void recordFailure(Claim claim, Exception cause) {
        log.warn("Reminder {} failed", claim.reminderId(), cause);
        release(claim, "error: " + cause.getMessage());
    }

    @Transactional
    public int recoverStaleReminders() {
        int recovered = reminderRepository.recoverStaleReminders(leaseMinutes, maxAttempts);
        if (recovered > 0) {
            log.info("Recovered {} reminders stranded in PROCESSING", recovered);
        }
        return recovered;
    }

    private void count(String outcome) {
        meters.counter("reminders.outcome", "outcome", outcome).increment();
    }
}
