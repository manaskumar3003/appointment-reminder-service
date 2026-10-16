package com.product.appointment.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.Optional;

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
/**
 * One row per reminder handed to the provider. UNIQUE(reminder_id) is the "never twice" guarantee.
 * Written SENDING before the provider is called and SENT once it answers, so a crash in between
 * leaves a row saying so.
 */
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reminder_id", nullable = false)
    private Long reminderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryStatus status;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    /** When the provider accepted it; null while SENDING. */
    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    /** Why the provider refused it, or why the outcome is unknown. */
    @Column(name = "last_error", length = 500)
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    private static final String KEY_PREFIX = "reminder-";

    /** One key per reminder, so every retry of it is the same request as far as the provider knows. */
    public static String idempotencyKey(Long reminderId) {
        return KEY_PREFIX + reminderId;
    }

    /** The reminder a key belongs to, for receipts that only carry our key. */
    public static Optional<Long> reminderIdOf(String idempotencyKey) {
        if (idempotencyKey == null || !idempotencyKey.startsWith(KEY_PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(idempotencyKey.substring(KEY_PREFIX.length())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getReminderId() {
        return reminderId;
    }

    public void setReminderId(Long reminderId) {
        this.reminderId = reminderId;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public void setStatus(DeliveryStatus status) {
        this.status = status;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public void setProviderMessageId(String providerMessageId) {
        this.providerMessageId = providerMessageId;
    }

    public OffsetDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(OffsetDateTime sentAt) {
        this.sentAt = sentAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError == null || lastError.length() <= 500 ? lastError : lastError.substring(0, 500);
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
