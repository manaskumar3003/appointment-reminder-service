package com.product.appointment.worker;

import com.product.appointment.entity.Appointment;
import com.product.appointment.entity.AppointmentStatus;
import com.product.appointment.entity.DeliveryStatus;
import com.product.appointment.entity.NotificationLog;
import com.product.appointment.entity.Reminder;
import com.product.appointment.entity.ReminderStatus;
import com.product.appointment.entity.ReminderType;
import com.product.appointment.notification.SendResult;
import com.product.appointment.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Whole polls, the way @Scheduled runs them: recover, claim, send each. */
class ReminderWorkerTest extends IntegrationTest {

    @Test
    void dueReminderIsSentOnceAndNeverAgainOnLaterPolls() {
        Reminder due = dueReminder();

        worker.processReminders();
        worker.processReminders();
        worker.processReminders();

        assertThat(notificationLogRepository.count()).isEqualTo(1);
        assertThat(notificationLogRepository.existsByReminderId(due.getId())).isTrue();
        Reminder after = reminderRepository.findById(due.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ReminderStatus.SENT);
        assertThat(after.getSentAt()).isNotNull();
    }

    /** A worker that dies mid-send strands a PROCESSING row; the reaper frees it. */
    @Test
    void reminderStrandedByADeadWorkerIsRetried() {
        Reminder stranded = dueReminder();
        stranded.setStatus(ReminderStatus.PROCESSING);
        stranded.setProcessingStartedAt(OffsetDateTime.now().minusHours(1));
        reminderRepository.saveAndFlush(stranded);

        worker.processReminders();

        assertThat(statusOf(stranded)).isEqualTo(ReminderStatus.SENT);
        assertThat(notificationLogRepository.count()).isEqualTo(1);
    }

    /**
     * The worker died after the provider took the message but before SENT was committed. The retry
     * probes first, finds the provider already has it, and records SENT without sending again.
     */
    @Test
    void sendInterruptedBeforeItWasConfirmedIsNotDeliveredTwice() {
        Reminder stranded = dueReminder();
        stranded.setStatus(ReminderStatus.PROCESSING);
        stranded.setProcessingStartedAt(OffsetDateTime.now().minusHours(1));
        stranded.setAttemptCount(1);
        reminderRepository.saveAndFlush(stranded);
        unconfirmedSend(stranded);
        String delivered = ((SendResult.Accepted) notificationSender.send(
                reminderRepository.findByIdWithAppointment(stranded.getId()).orElseThrow(),
                NotificationLog.idempotencyKey(stranded.getId()))).providerMessageId();

        worker.processReminders();

        NotificationLog entry = notificationLogRepository.findByReminderId(stranded.getId()).orElseThrow();
        assertThat(entry.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(entry.getProviderMessageId()).isEqualTo(delivered);
        assertThat(statusOf(stranded)).isEqualTo(ReminderStatus.SENT);
    }

    /** After an outage the backlog holds reminders for appointments that already happened. */
    @Test
    void backloggedReminderForAnAppointmentThatAlreadyStartedIsNotSent() {
        Appointment past = new Appointment();
        past.setDealershipId(7L);
        past.setCustomerContact("+15550001111");
        past.setScheduledAt(OffsetDateTime.now().minusHours(3));
        past.setStatus(AppointmentStatus.SCHEDULED);
        past = appointmentRepository.saveAndFlush(past);

        Reminder overdue = new Reminder();
        overdue.setAppointment(past);
        overdue.setReminderType(ReminderType.TWO_HOURS);
        overdue.setScheduledAt(OffsetDateTime.now().minusHours(5));
        overdue.setStatus(ReminderStatus.PENDING);
        overdue.setAttemptCount(0);
        overdue = reminderRepository.saveAndFlush(overdue);

        worker.processReminders();

        assertThat(notificationLogRepository.count()).isZero();
        assertThat(statusOf(overdue)).isEqualTo(ReminderStatus.SKIPPED);
    }

    /**
     * A failed send must wait out the backoff. Without it three attempts burn in 45 seconds
     * and a short provider outage fails every reminder in flight for good.
     */
    @Test
    void failedReminderIsHeldBackUntilTheRetryDelayHasElapsed() {
        Reminder due = claimed(dueReminder(), 1);

        reminderService.recordFailure(claimOf(due), new RuntimeException("provider down"));

        Reminder afterFailure = reminderRepository.findById(due.getId()).orElseThrow();
        assertThat(afterFailure.getStatus()).isEqualTo(ReminderStatus.PENDING);
        assertThat(afterFailure.getNextAttemptAt()).isAfter(OffsetDateTime.now());

        worker.processReminders();
        assertThat(notificationLogRepository.count()).isZero();

        // Once the delay has passed the retry goes through.
        afterFailure.setNextAttemptAt(OffsetDateTime.now().minusMinutes(1));
        reminderRepository.saveAndFlush(afterFailure);
        worker.processReminders();

        assertThat(notificationLogRepository.count()).isEqualTo(1);
        assertThat(statusOf(due)).isEqualTo(ReminderStatus.SENT);
    }
}
