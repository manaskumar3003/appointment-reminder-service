package com.product.appointment.service;

import com.product.appointment.entity.DeliveryStatus;
import com.product.appointment.entity.NotificationLog;
import com.product.appointment.entity.Reminder;
import com.product.appointment.entity.ReminderStatus;
import com.product.appointment.notification.SendResult;
import com.product.appointment.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Each ReminderService step on its own: send, record a failure, recover a stale lease, reconcile. */
class ReminderServiceTest extends IntegrationTest {

    /** Sending the same claimed reminder twice is a no-op, not a second notification. */
    @Test
    void sendingAnAlreadySentReminderIsIdempotent() {
        Reminder due = claimed(dueReminder(), 1);

        reminderService.sendClaimed(claimOf(due));
        reminderService.sendClaimed(claimOf(due));

        assertThat(notificationLogRepository.count()).isEqualTo(1);
        assertThat(statusOf(due)).isEqualTo(ReminderStatus.SENT);
    }

    /** Cancellation only touches PENDING rows - an already-claimed one must still stop. */
    @Test
    void appointmentCancelledAfterItsReminderWasClaimedIsNotSent() throws Exception {
        Reminder claimed = claimed(dueReminder(), 1);

        mockMvc.perform(post("/appointments/{id}/cancel", claimed.getAppointment().getId()))
                .andExpect(status().isOk());

        reminderService.sendClaimed(claimOf(claimed));

        assertThat(notificationLogRepository.count()).isZero();
        assertThat(statusOf(claimed)).isEqualTo(ReminderStatus.SKIPPED);
    }

    @Test
    void reminderOutOfAttemptsIsFailedRatherThanRetried() {
        Reminder spent = claimed(dueReminder(), 3);

        reminderService.recordFailure(claimOf(spent), new RuntimeException("provider down"));

        Reminder after = reminderRepository.findById(spent.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ReminderStatus.FAILED);
        assertThat(after.getNextAttemptAt()).isNull();
    }

    /**
     * A send outlives its lease, the reminder is reclaimed and sent by another worker, and the
     * first worker then reports its failure. The customer got the reminder, so it stays SENT -
     * even on the last attempt, where an unguarded write would have filed it as FAILED.
     */
    @Test
    void failureReportedAfterAnotherWorkerSentTheReminderDoesNotUndoIt() {
        Reminder due = claimed(dueReminder(), 3);
        reminderService.sendClaimed(claimOf(due));

        reminderService.recordFailure(claimOf(due), new RuntimeException("duplicate key"));

        Reminder after = reminderRepository.findById(due.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ReminderStatus.SENT);
        assertThat(after.getNextAttemptAt()).isNull();
        assertThat(notificationLogRepository.count()).isEqualTo(1);
    }

    /**
     * Fencing: worker A's lease expired and worker B reclaimed the reminder. A's late failure must
     * not reset B's claim - a status check alone can't tell them apart (both are PROCESSING).
     */
    @Test
    void staleWorkersFailureCannotResetANewerClaim() {
        Reminder reminder = claimed(dueReminder(), 1);
        ReminderService.Claim workerA = claimOf(reminder);
        Reminder reclaimedByB = claimed(reminderRepository.findById(reminder.getId()).orElseThrow(), 2);

        reminderService.recordFailure(workerA, new RuntimeException("A timed out"));

        Reminder after = reminderRepository.findById(reminder.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ReminderStatus.PROCESSING);
        assertThat(after.getClaimToken()).isEqualTo(reclaimedByB.getClaimToken());
    }

    /** Parked FAILED with a send still unconfirmed, but the provider has it: it was delivered. */
    @Test
    void unconfirmedSendTheProviderHasIsReconciledAsSent() {
        Reminder parked = dueReminder();
        parked.setStatus(ReminderStatus.FAILED);
        reminderRepository.saveAndFlush(parked);
        unconfirmedSend(parked);
        SendResult.Accepted delivered = (SendResult.Accepted) notificationSender.send(
                reminderRepository.findByIdWithAppointment(parked.getId()).orElseThrow(),
                NotificationLog.idempotencyKey(parked.getId()));

        deliveryFeedbackService.reconcileUnconfirmed();

        NotificationLog entry = logOf(parked);
        assertThat(entry.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(entry.getProviderMessageId()).isEqualTo(delivered.providerMessageId());
        assertThat(statusOf(parked)).isEqualTo(ReminderStatus.SENT);
    }

    /** Parked FAILED with a send still unconfirmed, and the provider never got it: nothing went out. */
    @Test
    void unconfirmedSendTheProviderNeverReceivedIsMarkedNotDelivered() {
        Reminder parked = dueReminder();
        parked.setStatus(ReminderStatus.FAILED);
        reminderRepository.saveAndFlush(parked);
        unconfirmedSend(parked);

        deliveryFeedbackService.reconcileUnconfirmed();

        assertThat(logOf(parked).getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(statusOf(parked)).isEqualTo(ReminderStatus.FAILED);
    }

    /** A reminder that kills its worker rather than throwing must still hit the cap. */
    @Test
    void reminderThatKeepsStrandingItsWorkerEventuallyFails() {
        Reminder poison = dueReminder();
        poison.setStatus(ReminderStatus.PROCESSING);
        poison.setProcessingStartedAt(OffsetDateTime.now().minusHours(1));
        poison.setAttemptCount(3);
        reminderRepository.saveAndFlush(poison);

        reminderService.recoverStaleReminders();

        assertThat(statusOf(poison)).isEqualTo(ReminderStatus.FAILED);
    }
}
