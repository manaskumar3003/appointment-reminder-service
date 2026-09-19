package com.product.appointment.dto;

import com.product.appointment.entity.AppointmentStatus;
import com.product.appointment.entity.ReminderStatus;
import com.product.appointment.entity.ReminderType;

import java.time.OffsetDateTime;
import java.util.List;

public class AppointmentResponse {

    private Long id;
    private Long dealershipId;
    private String customerContact;
    private OffsetDateTime scheduledAt;
    private AppointmentStatus status;

    /** Returned inline so a caller sees what was scheduled - including anything SKIPPED. */
    private List<ReminderView> reminders;

    public record ReminderView(Long id,
                               ReminderType type,
                               OffsetDateTime scheduledAt,
                               ReminderStatus status) {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDealershipId() {
        return dealershipId;
    }

    public void setDealershipId(Long dealershipId) {
        this.dealershipId = dealershipId;
    }

    public String getCustomerContact() {
        return customerContact;
    }

    public void setCustomerContact(String customerContact) {
        this.customerContact = customerContact;
    }

    public OffsetDateTime getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(OffsetDateTime scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public AppointmentStatus getStatus() {
        return status;
    }

    public void setStatus(AppointmentStatus status) {
        this.status = status;
    }

    public List<ReminderView> getReminders() {
        return reminders;
    }

    public void setReminders(List<ReminderView> reminders) {
        this.reminders = reminders;
    }
}
