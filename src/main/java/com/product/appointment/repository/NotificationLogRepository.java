package com.product.appointment.repository;

import com.product.appointment.entity.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Every write here is a conditional UPDATE - "change it only if it is still in a state that allows
 * it" - so late or duplicate answers can never move a delivery backwards (e.g. DELIVERED → FAILED).
 */
public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    boolean existsByReminderId(Long reminderId);

    Optional<NotificationLog> findByReminderId(Long reminderId);

    Optional<NotificationLog> findByProviderMessageId(String providerMessageId);

    /**
     * Records the provider's acceptance. An upsert, because the reconciler may have settled the row
     * while a slow call was still out - the message went, so the record says so.
     */
    @Modifying
    @Query(value = """
            INSERT INTO notification_log (reminder_id, status, provider_message_id, sent_at)
            VALUES (:reminderId, 'SENT', :providerMessageId, now())
            ON CONFLICT (reminder_id) DO UPDATE
            SET status = 'SENT',
                provider_message_id = EXCLUDED.provider_message_id,
                sent_at = EXCLUDED.sent_at,
                last_error = NULL
            WHERE notification_log.status IN ('SENDING', 'UNKNOWN', 'IN_DOUBT')
            """, nativeQuery = true)
    int markSent(@Param("reminderId") Long reminderId,
                 @Param("providerMessageId") String providerMessageId);

    /** A new attempt is about to be made: the previous one is known not to have delivered. */
    @Modifying
    @Query(value = """
            UPDATE notification_log SET status = 'SENDING', last_error = NULL
            WHERE reminder_id = :reminderId AND status IN ('UNKNOWN', 'FAILED')
            """, nativeQuery = true)
    int resetToSending(@Param("reminderId") Long reminderId);

    /** No usable answer: it may have gone out. The next attempt probes before resending. */
    @Modifying
    @Query(value = """
            UPDATE notification_log SET status = 'UNKNOWN', last_error = LEFT(:error, 500)
            WHERE reminder_id = :reminderId AND status = 'SENDING'
            """, nativeQuery = true)
    int markUnknown(@Param("reminderId") Long reminderId, @Param("error") String error);

    /** Definitely not delivered: refused by the provider, or a probe confirmed it never arrived. */
    @Modifying
    @Query(value = """
            UPDATE notification_log SET status = 'FAILED', last_error = LEFT(:error, 500)
            WHERE reminder_id = :reminderId AND status IN ('SENDING', 'UNKNOWN')
            """, nativeQuery = true)
    int markNotDelivered(@Param("reminderId") Long reminderId, @Param("error") String error);

    /** Unknown past the deadline and the provider still can't say: a human has to look. */
    @Modifying
    @Query(value = """
            UPDATE notification_log SET status = 'IN_DOUBT', last_error = LEFT(:error, 500)
            WHERE reminder_id = :reminderId AND status IN ('SENDING', 'UNKNOWN')
            """, nativeQuery = true)
    int markInDoubt(@Param("reminderId") Long reminderId, @Param("error") String error);

    /** Late receipt: it reached the handset. Wins over everything except itself (idempotent). */
    @Modifying
    @Query(value = """
            UPDATE notification_log
            SET status = 'DELIVERED',
                provider_message_id = COALESCE(provider_message_id, :providerMessageId),
                sent_at = COALESCE(sent_at, now()),
                last_error = NULL
            WHERE reminder_id = :reminderId AND status <> 'DELIVERED'
            """, nativeQuery = true)
    int markDelivered(@Param("reminderId") Long reminderId,
                      @Param("providerMessageId") String providerMessageId);

    /** Late receipt: the carrier couldn't deliver it. Never overrides a confirmed delivery. */
    @Modifying
    @Query(value = """
            UPDATE notification_log SET status = 'FAILED', last_error = LEFT(:error, 500)
            WHERE reminder_id = :reminderId AND status NOT IN ('DELIVERED', 'FAILED')
            """, nativeQuery = true)
    int markUndelivered(@Param("reminderId") Long reminderId, @Param("error") String error);

    /**
     * Unconfirmed sends that no retry will revisit: SENDING (crashed mid-send) or UNKNOWN (no answer)
     * whose reminder is already settled. Live reminders resolve themselves - their retry probes first.
     */
    @Query(value = """
            SELECT n.*
            FROM notification_log n
            JOIN reminders r ON r.id = n.reminder_id
            WHERE n.status IN ('SENDING', 'UNKNOWN')
              AND n.created_at < now() - (:minAgeMinutes * INTERVAL '1 minute')
              AND r.status NOT IN ('PENDING', 'PROCESSING')
            ORDER BY n.created_at
            LIMIT :limit
            """, nativeQuery = true)
    List<NotificationLog> findUnconfirmed(@Param("minAgeMinutes") int minAgeMinutes,
                                          @Param("limit") int limit);
}
