package com.product.appointment.notification;

import com.product.appointment.entity.Reminder;

public interface NotificationSender {

    void send(Reminder reminder);
}