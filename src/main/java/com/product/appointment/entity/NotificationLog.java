package com.product.appointment.entity;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(
        name = "notification_log",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_notification_reminder",
                        columnNames = "reminder_id"
                )
        }
)
/** One row per delivered reminder. UNIQUE(reminder_id) is the "never twice" guarantee. */
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reminder_id", nullable = false)
    private Long reminderId;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt;

    public Long getId() {
        return id;
    }

    public Long getReminderId() {
        return reminderId;
    }

    public void setReminderId(Long reminderId) {
        this.reminderId = reminderId;
    }

    public OffsetDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(OffsetDateTime sentAt) {
        this.sentAt = sentAt;
    }
}