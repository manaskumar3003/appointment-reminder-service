package com.product.appointment.support;

import com.product.appointment.entity.Appointment;
import com.product.appointment.entity.AppointmentStatus;
import com.product.appointment.entity.Reminder;
import com.product.appointment.entity.ReminderStatus;
import com.product.appointment.entity.ReminderType;
import com.product.appointment.notification.NotificationSender;
import com.product.appointment.repository.AppointmentRepository;
import com.product.appointment.repository.NotificationLogRepository;
import com.product.appointment.repository.ReminderRepository;
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
        Appointment appointment = new Appointment();
        appointment.setDealershipId(7L);
        appointment.setCustomerContact("+15550001111");
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

    /** The state a worker's claim leaves behind: PROCESSING, leased now, attempt counted. */
    protected Reminder claimed(Reminder reminder, int attemptCount) {
        reminder.setStatus(ReminderStatus.PROCESSING);
        reminder.setProcessingStartedAt(OffsetDateTime.now());
        reminder.setAttemptCount(attemptCount);
        return reminderRepository.saveAndFlush(reminder);
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
