package com.product.appointment.worker;

import com.product.appointment.service.DeliveryFeedbackService;
import com.product.appointment.service.ReminderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ReminderWorker {

    private static final Logger log = LoggerFactory.getLogger(ReminderWorker.class);

    private final ReminderService reminderService;
    private final DeliveryFeedbackService deliveryFeedbackService;

    public ReminderWorker(ReminderService reminderService, DeliveryFeedbackService deliveryFeedbackService) {
        this.reminderService = reminderService;
        this.deliveryFeedbackService = deliveryFeedbackService;
    }

    /** Loop is here, not in the service: self-invocation would bypass the proxy and drop @Transactional. */
    @Scheduled(fixedDelayString = "${reminders.poll-interval-ms:5000}",
               initialDelayString = "${reminders.initial-delay-ms:0}")
    public void processReminders() {
        try {
            reminderService.recoverStaleReminders();
            deliveryFeedbackService.reconcileUnconfirmed();

            List<ReminderService.Claim> claimed = reminderService.claimBatch();
            for (ReminderService.Claim claim : claimed) {
                try {
                    reminderService.sendClaimed(claim);
                } catch (Exception e) {
                    reminderService.recordFailure(claim, e);
                }
            }
        } catch (Exception e) {
            // Spring's default handler would also log and keep the schedule alive; catching here
            // just makes the failure say what it was and that the next poll retries it.
            log.error("Reminder poll failed; will retry on next interval", e);
        }
    }
}
