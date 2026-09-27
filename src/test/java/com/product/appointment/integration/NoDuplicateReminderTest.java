package com.product.appointment.integration;

import com.product.appointment.entity.Reminder;
import com.product.appointment.entity.ReminderStatus;
import com.product.appointment.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The "provable" bit of the brief: never the same reminder twice, from racing workers down to raw SQL. */
class NoDuplicateReminderTest extends IntegrationTest {

    @Test
    void concurrentWorkersCannotNotifyTheSameCustomerTwice() throws Exception {
        int workers = 8;
        int pollsPerWorker = 6;
        int dueReminders = 20;
        for (int i = 0; i < dueReminders; i++) {
            dueReminder();
        }

        CountDownLatch startLine = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        for (int i = 0; i < workers; i++) {
            pool.submit(() -> {
                startLine.await();
                for (int poll = 0; poll < pollsPerWorker; poll++) {
                    worker.processReminders();
                }
                return null;
            });
        }
        startLine.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(notificationLogRepository.count()).isEqualTo(dueReminders);
        assertThat(reminderRepository.findAll())
                .allMatch(r -> r.getStatus() == ReminderStatus.SENT);
    }

    /** The guarantee holds below the application: raw SQL can't record a second delivery either. */
    @Test
    void databaseRefusesASecondDeliveryOfTheSameReminder() {
        Long id = dueReminder().getId();
        jdbc.update("INSERT INTO notification_log (reminder_id) VALUES (?)", id);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO notification_log (reminder_id) VALUES (?)", id))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(notificationLogRepository.count()).isEqualTo(1);
    }

    @Test
    void databaseRefusesASecondReminderOfTheSameTypeForOneAppointment() {
        Reminder existing = dueReminder();

        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO reminders (appointment_id, reminder_type, scheduled_at, status)
                        VALUES (?, 'TWO_HOURS', now(), 'PENDING')
                        """, existing.getAppointment().getId()))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
