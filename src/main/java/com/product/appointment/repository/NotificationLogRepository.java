package com.product.appointment.repository;

import com.product.appointment.entity.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationLogRepository
        extends JpaRepository<NotificationLog, Long> {

    boolean existsByReminderId(Long reminderId);
}