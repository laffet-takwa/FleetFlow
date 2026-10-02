package com.fleetflow.notification.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.fleetflow.notification.entity.Notification;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByUserId(Long userId, Pageable pageable);

    Page<Notification> findByUserIdAndReadFalse(Long userId, Pageable pageable);

    long countByUserIdAndReadFalse(Long userId);

    /**
     * Loads one user's unread rows so each can be flipped and flushed individually,
     * which keeps {@code updated_at} honest and lets the caller report the rows it
     * touched.
     */
    List<Notification> findByUserIdAndReadFalse(Long userId);
}
