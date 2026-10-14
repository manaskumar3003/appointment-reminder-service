package com.product.appointment.repository;

import com.product.appointment.entity.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    boolean existsByReminderId(Long reminderId);

    Optional<NotificationLog> findByReminderId(Long reminderId);

    /**
     * Records the provider's answer. An upsert, because the reconciler may have cleared the
     * SENDING row while a slow call was still out - the message went, so the record comes back.
     */
    @Modifying
    @Query(value = """
            INSERT INTO notification_log (reminder_id, status, provider_message_id, sent_at)
            VALUES (:reminderId, 'SENT', :providerMessageId, now())
            ON CONFLICT (reminder_id) DO UPDATE
            SET status = 'SENT',
                provider_message_id = EXCLUDED.provider_message_id,
                sent_at = EXCLUDED.sent_at
            WHERE notification_log.status = 'SENDING'
            """, nativeQuery = true)
    int markSent(@Param("reminderId") Long reminderId,
                 @Param("providerMessageId") String providerMessageId);

    /**
     * Sends that were never confirmed and that no retry will revisit: the reminder is already
     * settled (FAILED or SKIPPED). A reminder still PENDING or PROCESSING resolves itself, because
     * its retry reuses the same idempotency key.
     */
    @Query(value = """
            SELECT n.*
            FROM notification_log n
            JOIN reminders r ON r.id = n.reminder_id
            WHERE n.status = 'SENDING'
              AND n.created_at < now() - (:minAgeMinutes * INTERVAL '1 minute')
              AND r.status NOT IN ('PENDING', 'PROCESSING')
            ORDER BY n.created_at
            LIMIT :limit
            """, nativeQuery = true)
    List<NotificationLog> findUnconfirmed(@Param("minAgeMinutes") int minAgeMinutes,
                                          @Param("limit") int limit);

    @Modifying
    @Query(value = "DELETE FROM notification_log WHERE reminder_id = :reminderId AND status = 'SENDING'",
            nativeQuery = true)
    int deleteUnconfirmed(@Param("reminderId") Long reminderId);
}
