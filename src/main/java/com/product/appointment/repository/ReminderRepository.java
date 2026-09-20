package com.product.appointment.repository;

import com.product.appointment.entity.Reminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {

    List<Reminder> findByAppointmentIdOrderByScheduledAt(Long appointmentId);

    /** Claims and marks PROCESSING in one statement; SKIP LOCKED keeps concurrent workers disjoint. */
    @Modifying
    @Query(value = """
            UPDATE reminders
            SET status = 'PROCESSING',
                processing_started_at = now(),
                attempt_count = attempt_count + 1
            WHERE id IN (
                SELECT r.id
                FROM reminders r
                JOIN appointments a ON a.id = r.appointment_id
                WHERE r.status = 'PENDING'
                  AND r.scheduled_at <= now()
                  AND (r.next_attempt_at IS NULL OR r.next_attempt_at <= now())
                  AND a.status = 'SCHEDULED'
                ORDER BY r.scheduled_at
                LIMIT :limit
                FOR UPDATE OF r SKIP LOCKED
            )
            RETURNING id
            """, nativeQuery = true)
    List<Long> claimDueReminders(@Param("limit") int limit);

    /** Frees leases left by a dead worker; ones out of attempts are parked in FAILED, not looped forever. */
    @Modifying
    @Query(value = """
            UPDATE reminders
            SET status = CASE WHEN attempt_count >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
                processing_started_at = NULL
            WHERE status = 'PROCESSING'
              AND processing_started_at < now() - (:leaseMinutes * INTERVAL '1 minute')
            """, nativeQuery = true)
    int recoverStaleReminders(@Param("leaseMinutes") int leaseMinutes,
                              @Param("maxAttempts") int maxAttempts);

    @Modifying
    @Query(value = """
            UPDATE reminders
            SET status = 'SKIPPED'
            WHERE appointment_id = :appointmentId
              AND status = 'PENDING'
            """, nativeQuery = true)
    int cancelPendingForAppointment(@Param("appointmentId") Long appointmentId);
}
