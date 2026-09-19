package com.product.appointment.worker;

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

    public ReminderWorker(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    /** Loop is here, not in the service: self-invocation would bypass the proxy and drop @Transactional. */
    @Scheduled(fixedDelayString = "${reminders.poll-interval-ms:15000}",
               initialDelayString = "${reminders.initial-delay-ms:0}")
    public void processReminders() {
        try {
            reminderService.recoverStaleReminders();

            List<Long> claimed = reminderService.claimBatch();
            for (Long id : claimed) {
                try {
                    reminderService.sendClaimed(id);
                } catch (Exception e) {
                    reminderService.recordFailure(id, e);
                }
            }
        } catch (Exception e) {
            // An exception escaping @Scheduled cancels the schedule for the life of the JVM.
            log.error("Reminder poll failed; will retry on next interval", e);
        }
    }
}
