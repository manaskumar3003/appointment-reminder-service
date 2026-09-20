package com.product.appointment.service;

import com.product.appointment.entity.Appointment;
import com.product.appointment.entity.AppointmentStatus;
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

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    private final ReminderRepository reminderRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final NotificationSender notificationSender;
    private final int batchSize;
    private final int maxAttempts;
    private final int leaseMinutes;
    private final int retryDelayMinutes;

    public ReminderService(ReminderRepository reminderRepository,
                           NotificationLogRepository notificationLogRepository,
                           NotificationSender notificationSender,
                           @Value("${reminders.batch-size:500}") int batchSize,
                           @Value("${reminders.max-attempts:3}") int maxAttempts,
                           @Value("${reminders.lease-minutes:5}") int leaseMinutes,
                           @Value("${reminders.retry-delay-minutes:5}") int retryDelayMinutes) {
        this.reminderRepository = reminderRepository;
        this.notificationLogRepository = notificationLogRepository;
        this.notificationSender = notificationSender;
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

    /** One transaction per reminder, and the log insert lives here so no sender can forget it. */
    @Transactional
    public void sendClaimed(Long reminderId) {

        Reminder reminder = reminderRepository.findById(reminderId).orElseThrow();

        String stale = staleReason(reminder.getAppointment());
        if (stale != null) {
            reminder.setStatus(ReminderStatus.SKIPPED);
            reminder.setProcessingStartedAt(null);
            log.info("Skipping reminder {}: {}", reminderId, stale);
            return;
        }

        if (notificationLogRepository.existsByReminderId(reminderId)) {
            log.info("Reminder {} already notified; not re-sending", reminderId);
        } else {
            NotificationLog entry = new NotificationLog();
            entry.setReminderId(reminderId);
            entry.setSentAt(OffsetDateTime.now());
            // UNIQUE(reminder_id) refuses a second delivery; the check above is only a fast path.
            notificationLogRepository.saveAndFlush(entry);
            notificationSender.send(reminder);
        }

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

    /** Fresh transaction - the failed send's transaction is already rolled back. */
    @Transactional
    public void recordFailure(Long reminderId, Exception cause) {
        Reminder reminder = reminderRepository.findById(reminderId).orElse(null);
        if (reminder == null) {
            return;
        }
        boolean exhausted = reminder.getAttemptCount() >= maxAttempts;
        reminder.setStatus(exhausted ? ReminderStatus.FAILED : ReminderStatus.PENDING);
        reminder.setProcessingStartedAt(null);
        // Hold it back before retrying: the failures worth retrying are rate limits and
        // outages, and hammering the next poll would spend every attempt inside a minute.
        reminder.setNextAttemptAt(exhausted ? null
                : OffsetDateTime.now().plusMinutes(retryDelayMinutes));
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
}
