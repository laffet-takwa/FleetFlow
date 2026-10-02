package com.fleetflow.notification.mapper;

import org.springframework.stereotype.Component;

import com.fleetflow.notification.dto.NotificationResponse;
import com.fleetflow.notification.entity.Notification;

@Component
public class NotificationMapper {

    public NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType().name(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getOrderId(),
                notification.getDeliveryId(),
                notification.getLevel().name(),
                notification.isRead(),
                notification.getCreatedAt());
    }
}
