package com.product.appointment.support;

import com.product.appointment.entity.Appointment;
import com.product.appointment.entity.AppointmentStatus;
import com.product.appointment.entity.NotificationLog;
import com.product.appointment.entity.Reminder;
import com.product.appointment.entity.ReminderStatus;
import com.product.appointment.entity.ReminderType;
import com.product.appointment.notification.NotificationSender;
import com.product.appointment.repository.AppointmentRepository;
import com.product.appointment.repository.NotificationLogRepository;
import com.product.appointment.repository.ReminderRepository;
import com.product.appointment.service.DeliveryFeedbackService;
import com.product.appointment.service.ReminderService;
import com.product.appointment.worker.ReminderWorker;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Shared setup for every test against the real application and a real Postgres.
 *
 * Every subclass inherits the same configuration, so Spring caches one context and one
 * container for the whole suite. The scheduler is parked so each test drives the worker by hand.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "reminders.initial-delay-ms=600000",
        "reminders.poll-interval-ms=600000",
        // One row per claim, so concurrent workers actually contend.
        "reminders.batch-size=1"
})
public abstract class IntegrationTest {

    @Autowired protected MockMvc mockMvc;
    @Autowired protected AppointmentRepository appointmentRepository;
    @Autowired protected ReminderRepository reminderRepository;
    @Autowired protected NotificationLogRepository notificationLogRepository;
    @Autowired protected ReminderService reminderService;
    @Autowired protected DeliveryFeedbackService deliveryFeedbackService;
    @Autowired protected ReminderWorker worker;
    @Autowired protected NotificationSender notificationSender;
    @Autowired protected JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        notificationLogRepository.deleteAllInBatch();
        reminderRepository.deleteAllInBatch();
        appointmentRepository.deleteAllInBatch();
    }

    /** An appointment whose 2h reminder is already due. */
    protected Reminder dueReminder() {
        return dueReminder("+15550001111");
    }

    /**
     * Same, for a given contact. The stub provider picks its behaviour from the last four digits:
     * 0000 reply lost · 7777 provider down · 8888 rate-limited once · 9999 invalid number.
     */
    protected Reminder dueReminder(String contact) {
        Appointment appointment = new Appointment();
        appointment.setDealershipId(7L);
        appointment.setCustomerContact(contact);
        appointment.setScheduledAt(OffsetDateTime.now().plusDays(2));
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        appointment = appointmentRepository.saveAndFlush(appointment);

        Reminder reminder = new Reminder();
        reminder.setAppointment(appointment);
        reminder.setReminderType(ReminderType.TWO_HOURS);
        reminder.setScheduledAt(OffsetDateTime.now().minusMinutes(1));
        reminder.setStatus(ReminderStatus.PENDING);
        reminder.setAttemptCount(0);
        return reminderRepository.saveAndFlush(reminder);
    }

    /** The state a worker's claim leaves behind: PROCESSING, leased now, attempt counted, fencing token set. */
    protected Reminder claimed(Reminder reminder, int attemptCount) {
        reminder.setStatus(ReminderStatus.PROCESSING);
        reminder.setProcessingStartedAt(OffsetDateTime.now());
        reminder.setAttemptCount(attemptCount);
        reminder.setClaimToken(UUID.randomUUID().toString());
        return reminderRepository.saveAndFlush(reminder);
    }

    /** The claim a worker would hold for this reminder. */
    protected ReminderService.Claim claimOf(Reminder reminder) {
        return new ReminderService.Claim(reminder.getId(), reminder.getClaimToken());
    }

    /** Lets a reminder that was sent back with a retry delay be claimed on the next poll. */
    protected void retryDelayPassed(Reminder reminder) {
        jdbc.update("UPDATE reminders SET next_attempt_at = now() - interval '1 minute' WHERE id = ?",
                reminder.getId());
    }

    protected NotificationLog logOf(Reminder reminder) {
        return notificationLogRepository.findByReminderId(reminder.getId()).orElseThrow();
    }

    /** What a crash after recording the intent leaves behind: a SENDING row, an hour old. */
    protected void unconfirmedSend(Reminder reminder) {
        jdbc.update("""
                INSERT INTO notification_log (reminder_id, status, created_at)
                VALUES (?, 'SENDING', now() - interval '1 hour')
                """, reminder.getId());
    }

    protected ReminderStatus statusOf(Reminder reminder) {
        return reminderRepository.findById(reminder.getId()).orElseThrow().getStatus();
    }
}
