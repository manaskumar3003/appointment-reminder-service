package com.product.appointment.integration;

import com.product.appointment.entity.DeliveryStatus;
import com.product.appointment.entity.NotificationLog;
import com.product.appointment.entity.Reminder;
import com.product.appointment.entity.ReminderStatus;
import com.product.appointment.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The provider can't be trusted to answer. Each test is one path of the flow: lost reply, provider
 * down, rejections, late receipts - and in none of them does a customer get the same reminder twice.
 */
class UnreliableProviderTest extends IntegrationTest {

    @Autowired MeterRegistry meters;

    private double outcomes(String outcome) {
        return meters.counter("reminders.outcome", "outcome", outcome).count();
    }

    /** The message went out but the reply was lost: UNKNOWN, then the retry probes and finds it. */
    @Test
    void lostReplyIsProbedOnRetryNotResent() {
        Reminder reminder = dueReminder("+15550000000");
        double foundBefore = outcomes("probe_found");

        worker.processReminders();

        assertThat(logOf(reminder).getStatus()).isEqualTo(DeliveryStatus.UNKNOWN);
        Reminder afterFirst = reminderRepository.findById(reminder.getId()).orElseThrow();
        assertThat(afterFirst.getStatus()).isEqualTo(ReminderStatus.PENDING);
        assertThat(afterFirst.getNextAttemptAt()).isNotNull();

        retryDelayPassed(reminder);
        worker.processReminders();

        NotificationLog entry = logOf(reminder);
        assertThat(entry.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(entry.getProviderMessageId())
                .isEqualTo(notificationSender.findSent(NotificationLog.idempotencyKey(reminder.getId())).orElseThrow());
        assertThat(statusOf(reminder)).isEqualTo(ReminderStatus.SENT);
        assertThat(outcomes("probe_found")).isEqualTo(foundBefore + 1);
    }

    /** An invalid number is refused for good: FAILED at once, no retries. */
    @Test
    void permanentRejectionFailsWithoutRetrying() {
        Reminder reminder = dueReminder("+15559999999");

        worker.processReminders();

        assertThat(logOf(reminder).getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(logOf(reminder).getLastError()).contains("invalid number");
        Reminder after = reminderRepository.findById(reminder.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ReminderStatus.FAILED);
        assertThat(after.getAttemptCount()).isEqualTo(1);
    }

    /** A definite refusal (rate limit) frees the reminder for a plain retry, which then goes through. */
    @Test
    void retryableRejectionIsRetriedAndThenSent() {
        Reminder reminder = dueReminder("+15558888888");

        worker.processReminders();

        assertThat(logOf(reminder).getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(statusOf(reminder)).isEqualTo(ReminderStatus.PENDING);

        retryDelayPassed(reminder);
        worker.processReminders();

        assertThat(logOf(reminder).getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(statusOf(reminder)).isEqualTo(ReminderStatus.SENT);
        assertThat(notificationLogRepository.count()).isEqualTo(1);
    }

    /**
     * The provider is down: no answer to the send, and every probe fails too. It is never resent
     * blindly; once out of attempts and past the deadline it is IN_DOUBT for a human.
     */
    @Test
    void providerThatNeverAnswersEndsInDoubtNeverResent() {
        Reminder reminder = dueReminder("+15557777777");
        double sentBefore = outcomes("sent");

        worker.processReminders();
        assertThat(logOf(reminder).getStatus()).isEqualTo(DeliveryStatus.UNKNOWN);

        // The retry can't confirm either: still UNKNOWN, not resent.
        retryDelayPassed(reminder);
        worker.processReminders();
        assertThat(logOf(reminder).getStatus()).isEqualTo(DeliveryStatus.UNKNOWN);
        assertThat(outcomes("sent")).isEqualTo(sentBefore);

        // Out of attempts, and the unknown is older than the 30-minute deadline.
        jdbc.update("UPDATE reminders SET status = 'FAILED', claim_token = NULL WHERE id = ?", reminder.getId());
        jdbc.update("UPDATE notification_log SET created_at = now() - interval '1 hour' WHERE reminder_id = ?",
                reminder.getId());
        deliveryFeedbackService.reconcileUnconfirmed();

        assertThat(logOf(reminder).getStatus()).isEqualTo(DeliveryStatus.IN_DOUBT);
        assertThat(outcomes("sent")).isEqualTo(sentBefore);
    }

    /** A signed receipt marks it DELIVERED; the same receipt again changes nothing. */
    @Test
    void deliveryReceiptIsAppliedOnceAndIsIdempotent() throws Exception {
        Reminder reminder = dueReminder();
        worker.processReminders();
        String key = NotificationLog.idempotencyKey(reminder.getId());

        receipt(key, "DELIVERED", sign(key + "||DELIVERED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(true));
        receipt(key, "DELIVERED", sign(key + "||DELIVERED"))
                .andExpect(status().isOk());

        assertThat(logOf(reminder).getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(statusOf(reminder)).isEqualTo(ReminderStatus.SENT);
    }

    /** Receipts arrive out of order: a late "undelivered" never overrides a confirmed delivery. */
    @Test
    void lateUndeliveredReceiptCannotOverrideDelivered() throws Exception {
        Reminder reminder = dueReminder();
        worker.processReminders();
        String key = NotificationLog.idempotencyKey(reminder.getId());

        receipt(key, "DELIVERED", sign(key + "||DELIVERED")).andExpect(status().isOk());
        receipt(key, "UNDELIVERED", sign(key + "||UNDELIVERED")).andExpect(status().isOk());

        assertThat(logOf(reminder).getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
    }

    /** A receipt resolves an UNKNOWN that no probe could: the provider answered late. */
    @Test
    void lateReceiptResolvesAnUnknownSend() throws Exception {
        Reminder reminder = dueReminder("+15550000000");
        worker.processReminders();
        assertThat(logOf(reminder).getStatus()).isEqualTo(DeliveryStatus.UNKNOWN);
        String key = NotificationLog.idempotencyKey(reminder.getId());

        receipt(key, "DELIVERED", sign(key + "||DELIVERED")).andExpect(status().isOk());

        assertThat(logOf(reminder).getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(statusOf(reminder)).isEqualTo(ReminderStatus.SENT);
    }

    @Test
    void unsignedOrForgedReceiptIsRefused() throws Exception {
        Reminder reminder = dueReminder();
        worker.processReminders();
        String key = NotificationLog.idempotencyKey(reminder.getId());

        receipt(key, "UNDELIVERED", "deadbeef").andExpect(status().isUnauthorized());

        assertThat(logOf(reminder).getStatus()).isEqualTo(DeliveryStatus.SENT);
    }

    @Test
    void receiptForAnUnknownMessageIsAcknowledgedAndIgnored() throws Exception {
        receipt("reminder-999999", "DELIVERED", sign("reminder-999999||DELIVERED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(false));
    }

    private org.springframework.test.web.servlet.ResultActions receipt(String key, String status, String signature)
            throws Exception {
        return mockMvc.perform(post("/webhooks/delivery-status")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Signature", signature)
                .content("""
                        {"idempotencyKey":"%s","status":"%s"}
                        """.formatted(key, status)));
    }

    private static String sign(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("dev-webhook-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
