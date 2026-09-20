package com.product.appointment;

import com.product.appointment.entity.Appointment;
import com.product.appointment.entity.AppointmentStatus;
import com.product.appointment.entity.Reminder;
import com.product.appointment.entity.ReminderStatus;
import com.product.appointment.entity.ReminderType;
import com.product.appointment.repository.AppointmentRepository;
import com.product.appointment.repository.NotificationLogRepository;
import com.product.appointment.repository.ReminderRepository;
import com.product.appointment.service.ReminderService;
import com.product.appointment.worker.ReminderWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The scheduler is parked so each test drives the worker by hand. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "reminders.initial-delay-ms=600000",
        "reminders.poll-interval-ms=600000",
        // One row per claim, so concurrent workers actually contend.
        "reminders.batch-size=1"
})
class ReminderFlowTest {

    @Autowired MockMvc mockMvc;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired ReminderRepository reminderRepository;
    @Autowired NotificationLogRepository notificationLogRepository;
    @Autowired ReminderService reminderService;
    @Autowired ReminderWorker worker;

    @BeforeEach
    void clean() {
        notificationLogRepository.deleteAllInBatch();
        reminderRepository.deleteAllInBatch();
        appointmentRepository.deleteAllInBatch();
    }

    @Test
    void createsOneReminderTwentyFourHoursOutAndOneTwoHoursOut() throws Exception {
        OffsetDateTime slot = OffsetDateTime.now().plusDays(3).truncatedTo(ChronoUnit.SECONDS);

        mockMvc.perform(post("/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(7L, "+15550001111", slot)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.reminders.length()").value(2))
                .andExpect(jsonPath("$.reminders[0].type").value("TWENTY_FOUR_HOURS"))
                .andExpect(jsonPath("$.reminders[1].type").value("TWO_HOURS"));

        List<Reminder> reminders = remindersInTypeOrder();
        assertThat(reminders).hasSize(2);
        assertThat(reminders.get(0).getReminderType()).isEqualTo(ReminderType.TWENTY_FOUR_HOURS);
        assertThat(reminders.get(0).getScheduledAt().toInstant())
                .isEqualTo(slot.minusHours(24).toInstant());
        assertThat(reminders.get(1).getReminderType()).isEqualTo(ReminderType.TWO_HOURS);
        assertThat(reminders.get(1).getScheduledAt().toInstant())
                .isEqualTo(slot.minusHours(2).toInstant());
        assertThat(reminders).allMatch(r -> r.getStatus() == ReminderStatus.PENDING);
    }

    /** Booking inside the 24h window must not fire "your appointment is in 24 hours" now. */
    @Test
    void reminderWhoseSendTimeHasAlreadyPassedIsSkippedNotSentLate() throws Exception {
        mockMvc.perform(post("/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(7L, "+15550001111", OffsetDateTime.now().plusHours(3))))
                .andExpect(status().isCreated());

        List<Reminder> reminders = remindersInTypeOrder();
        assertThat(reminders.get(0).getStatus()).isEqualTo(ReminderStatus.SKIPPED);
        assertThat(reminders.get(1).getStatus()).isEqualTo(ReminderStatus.PENDING);

        worker.processReminders();
        assertThat(notificationLogRepository.count()).isZero();
    }

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

    /** The "provable" bit of the brief. */
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

    /** Sending the same claimed reminder twice is a no-op, not a second notification. */
    @Test
    void sendingAnAlreadySentReminderIsIdempotent() {
        Reminder due = dueReminder();

        reminderService.sendClaimed(due.getId());
        reminderService.sendClaimed(due.getId());

        assertThat(notificationLogRepository.count()).isEqualTo(1);
    }

    @Test
    void cancellingAnAppointmentStopsItsPendingReminders() throws Exception {
        Reminder due = dueReminder();
        Long appointmentId = due.getAppointment().getId();

        mockMvc.perform(post("/appointments/{id}/cancel", appointmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        worker.processReminders();

        assertThat(notificationLogRepository.count()).isZero();
        assertThat(reminderRepository.findById(due.getId()).orElseThrow().getStatus())
                .isEqualTo(ReminderStatus.SKIPPED);
    }

    @Test
    void cancellingTwiceIsNotAnError() throws Exception {
        Long id = dueReminder().getAppointment().getId();
        mockMvc.perform(post("/appointments/{id}/cancel", id)).andExpect(status().isOk());
        mockMvc.perform(post("/appointments/{id}/cancel", id)).andExpect(status().isOk());
    }

    /** A worker that dies mid-send strands a PROCESSING row; the reaper frees it. */
    @Test
    void reminderStrandedByADeadWorkerIsRetried() {
        Reminder stranded = dueReminder();
        stranded.setStatus(ReminderStatus.PROCESSING);
        stranded.setProcessingStartedAt(OffsetDateTime.now().minusHours(1));
        reminderRepository.saveAndFlush(stranded);

        worker.processReminders();

        assertThat(reminderRepository.findById(stranded.getId()).orElseThrow().getStatus())
                .isEqualTo(ReminderStatus.SENT);
        assertThat(notificationLogRepository.count()).isEqualTo(1);
    }

    /** Cancellation only touches PENDING rows - an already-claimed one must still stop. */
    @Test
    void appointmentCancelledAfterItsReminderWasClaimedIsNotSent() throws Exception {
        Reminder claimed = dueReminder();
        Long appointmentId = claimed.getAppointment().getId();
        claimed.setStatus(ReminderStatus.PROCESSING);
        claimed.setProcessingStartedAt(OffsetDateTime.now());
        reminderRepository.saveAndFlush(claimed);

        mockMvc.perform(post("/appointments/{id}/cancel", appointmentId))
                .andExpect(status().isOk());

        reminderService.sendClaimed(claimed.getId());

        assertThat(notificationLogRepository.count()).isZero();
        assertThat(reminderRepository.findById(claimed.getId()).orElseThrow().getStatus())
                .isEqualTo(ReminderStatus.SKIPPED);
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
        assertThat(reminderRepository.findById(overdue.getId()).orElseThrow().getStatus())
                .isEqualTo(ReminderStatus.SKIPPED);
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

        assertThat(reminderRepository.findById(poison.getId()).orElseThrow().getStatus())
                .isEqualTo(ReminderStatus.FAILED);
    }

    @Test
    void oversizedContactIsRejected() throws Exception {
        mockMvc.perform(post("/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(7L, "x".repeat(300), OffsetDateTime.now().plusDays(2))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonPositiveDealershipIsRejected() throws Exception {
        mockMvc.perform(post("/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(0L, "+15550001111", OffsetDateTime.now().plusDays(2))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void appointmentInThePastIsRejected() throws Exception {
        mockMvc.perform(post("/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(7L, "+15550001111", OffsetDateTime.now().minusDays(1))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingContactIsRejected() throws Exception {
        mockMvc.perform(post("/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dealershipId":7,"customerContact":"  ","scheduledAt":"%s"}
                                """.formatted(OffsetDateTime.now().plusDays(2))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unparseableTimestampIsFourHundredNotFiveHundred() throws Exception {
        mockMvc.perform(post("/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dealershipId":7,"customerContact":"a@b.com","scheduledAt":"tuesday-ish"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownAppointmentIsFourOhFour() throws Exception {
        mockMvc.perform(get("/appointments/{id}", 999999L)).andExpect(status().isNotFound());
    }

    private String body(Long dealershipId, String contact, OffsetDateTime at) {
        return """
                {"dealershipId":%d,"customerContact":"%s","scheduledAt":"%s"}
                """.formatted(dealershipId, contact, at);
    }

    private List<Reminder> remindersInTypeOrder() {
        return reminderRepository.findAll().stream()
                .sorted(Comparator.comparing(Reminder::getScheduledAt))
                .toList();
    }

    /** An appointment whose 2h reminder is already due. */
    private Reminder dueReminder() {
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
}
