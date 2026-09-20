package com.product.appointment;

import com.product.appointment.dto.CreateAppointmentRequest;
import com.product.appointment.service.AppointmentService;
import com.product.appointment.worker.ReminderWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Off by default - run with ./mvnw test -Dbenchmark=true -Dtest=ReminderBenchmarkTest.
 * Numbers land in docs/benchmarks.md.
 *
 * Notification logging is turned down to WARN: at this volume the console writes would
 * dominate, and what is being measured is the database path.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "reminders.initial-delay-ms=86400000",
        "reminders.poll-interval-ms=86400000",
        "logging.level.com.product.appointment.notification=WARN",
        "logging.level.com.product.appointment.service=WARN"
})
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
class ReminderBenchmarkTest {

    private static final int APPOINTMENTS = 50_000;
    private static final int BOOKINGS = 2_000;
    private static final int WORKERS = 4;

    @Autowired JdbcTemplate jdbc;
    @Autowired AppointmentService appointmentService;
    @Autowired ReminderWorker worker;

    @Test
    void bookingThroughput() throws Exception {
        truncate();
        int threads = 8;
        int perThread = BOOKINGS / threads;

        CountDownLatch startLine = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long start = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                startLine.await();
                for (int i = 0; i < perThread; i++) {
                    CreateAppointmentRequest request = new CreateAppointmentRequest();
                    request.setDealershipId((long) (i % 500) + 1);
                    request.setCustomerContact("+15550000000");
                    request.setScheduledAt(OffsetDateTime.now().plusDays(3));
                    appointmentService.createAppointment(request);
                }
                return null;
            });
        }
        startLine.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.MINUTES);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        int booked = threads * perThread;
        report("booking", booked + " appointments (" + booked * 2 + " reminder rows), "
                + threads + " threads", booked, elapsed);
    }

    @Test
    void drainThroughput() throws Exception {
        truncate();
        seed(APPOINTMENTS);

        AtomicInteger polls = new AtomicInteger();
        CountDownLatch startLine = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        long start = System.nanoTime();
        for (int t = 0; t < WORKERS; t++) {
            pool.submit(() -> {
                startLine.await();
                while (pending() > 0) {
                    worker.processReminders();
                    polls.incrementAndGet();
                }
                return null;
            });
        }
        startLine.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.MINUTES);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        int sent = jdbc.queryForObject("SELECT count(*) FROM notification_log", Integer.class);
        report("drain", sent + " reminders, " + WORKERS + " workers, "
                + polls.get() + " polls", sent, elapsed);

        // Also a dedup test at volume: one delivery each, no more, under four-way contention.
        assertThat(sent).isEqualTo(APPOINTMENTS);
    }

    private void report(String name, String what, int count, Duration elapsed) {
        double seconds = elapsed.toMillis() / 1000.0;
        System.out.printf("%nBENCHMARK %s: %s in %.2fs = %.0f/sec (%.2f ms each)%n%n",
                name, what, seconds, count / seconds, elapsed.toMillis() / (double) count);
    }

    private int pending() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM reminders WHERE status = 'PENDING'", Integer.class);
    }

    /** One statement - seeding is not what is being measured. */
    private void seed(int appointments) {
        jdbc.update("""
                INSERT INTO appointments (dealership_id, customer_contact, scheduled_at, status)
                SELECT (i %% 500) + 1, '+1555' || lpad(i::text, 7, '0'),
                       now() + interval '2 hours', 'SCHEDULED'
                FROM generate_series(1, %d) i
                """.formatted(appointments));
        jdbc.update("""
                INSERT INTO reminders (appointment_id, reminder_type, scheduled_at, status)
                SELECT id, 'TWO_HOURS', now() - interval '1 minute', 'PENDING' FROM appointments
                """);
        jdbc.update("ANALYZE reminders");
    }

    private void truncate() {
        jdbc.update("TRUNCATE notification_log, reminders, appointments RESTART IDENTITY CASCADE");
    }
}
