package com.product.appointment.service;

import com.product.appointment.entity.Reminder;
import com.product.appointment.entity.ReminderStatus;
import com.product.appointment.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Each ReminderService step on its own: send, record a failure, recover a stale lease. */
class ReminderServiceTest extends IntegrationTest {

    /** Sending the same claimed reminder twice is a no-op, not a second notification. */
    @Test
    void sendingAnAlreadySentReminderIsIdempotent() {
        Reminder due = dueReminder();

        reminderService.sendClaimed(due.getId());
        reminderService.sendClaimed(due.getId());

        assertThat(notificationLogRepository.count()).isEqualTo(1);
    }

    /** Cancellation only touches PENDING rows - an already-claimed one must still stop. */
    @Test
    void appointmentCancelledAfterItsReminderWasClaimedIsNotSent() throws Exception {
        Reminder claimed = claimed(dueReminder(), 1);

        mockMvc.perform(post("/appointments/{id}/cancel", claimed.getAppointment().getId()))
                .andExpect(status().isOk());

        reminderService.sendClaimed(claimed.getId());

        assertThat(notificationLogRepository.count()).isZero();
        assertThat(statusOf(claimed)).isEqualTo(ReminderStatus.SKIPPED);
    }

    @Test
    void reminderOutOfAttemptsIsFailedRatherThanRetried() {
        Reminder spent = claimed(dueReminder(), 3);

        reminderService.recordFailure(spent.getId(), new RuntimeException("provider down"));

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
        reminderService.sendClaimed(due.getId());

        reminderService.recordFailure(due.getId(), new RuntimeException("duplicate key"));

        Reminder after = reminderRepository.findById(due.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ReminderStatus.SENT);
        assertThat(after.getNextAttemptAt()).isNull();
        assertThat(notificationLogRepository.count()).isEqualTo(1);
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
