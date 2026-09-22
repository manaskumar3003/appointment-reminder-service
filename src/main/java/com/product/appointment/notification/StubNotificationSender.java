package com.product.appointment.notification;

import com.product.appointment.entity.Reminder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Logs the payload and sends nothing. Dedup lives in ReminderService, so this only has to deliver. */
@Component
public class StubNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(StubNotificationSender.class);

    @Override
    public void send(Reminder reminder) {
        log.info("NOTIFICATION reminderId={} appointmentId={} type={} contact={} appointmentAt={}",
                reminder.getId(),
                reminder.getAppointment().getId(),
                reminder.getReminderType(),
                mask(reminder.getAppointment().getCustomerContact()),
                reminder.getAppointment().getScheduledAt());
    }

    /** Enough to tell two customers apart in a log, not enough to be a leak. */
    private static String mask(String contact) {
        if (contact == null || contact.length() < 6) {
            return "****";
        }
        return contact.substring(0, 3) + "****" + contact.substring(contact.length() - 2);
    }
}
