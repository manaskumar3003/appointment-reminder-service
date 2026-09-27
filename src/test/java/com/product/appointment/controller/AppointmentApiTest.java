package com.product.appointment.controller;

import com.product.appointment.entity.Reminder;
import com.product.appointment.entity.ReminderStatus;
import com.product.appointment.entity.ReminderType;
import com.product.appointment.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP contract: booking, cancelling, validation, and the status of every error. */
class AppointmentApiTest extends IntegrationTest {

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
    void cancellingAnAppointmentStopsItsPendingReminders() throws Exception {
        Reminder due = dueReminder();
        Long appointmentId = due.getAppointment().getId();

        mockMvc.perform(post("/appointments/{id}/cancel", appointmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        worker.processReminders();

        assertThat(notificationLogRepository.count()).isZero();
        assertThat(statusOf(due)).isEqualTo(ReminderStatus.SKIPPED);
    }

    @Test
    void cancellingTwiceIsNotAnError() throws Exception {
        Long id = dueReminder().getAppointment().getId();
        mockMvc.perform(post("/appointments/{id}/cancel", id)).andExpect(status().isOk());
        mockMvc.perform(post("/appointments/{id}/cancel", id)).andExpect(status().isOk());
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

    /** Spring's own errors must keep their status, not be swallowed by the catch-all as 500s. */
    @Test
    void wrongMethodIsFourOhFiveNotFiveHundred() throws Exception {
        mockMvc.perform(delete("/appointments/{id}", 1L)).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void nonNumericIdIsFourHundredNotFiveHundred() throws Exception {
        mockMvc.perform(get("/appointments/abc")).andExpect(status().isBadRequest());
    }

    @Test
    void unknownPathIsFourOhFourNotFiveHundred() throws Exception {
        mockMvc.perform(get("/no-such-path")).andExpect(status().isNotFound());
    }

    @Test
    void wrongContentTypeIsFourFifteenNotFiveHundred() throws Exception {
        mockMvc.perform(post("/appointments").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void healthReportsUpWithTheDatabase() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
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
}
