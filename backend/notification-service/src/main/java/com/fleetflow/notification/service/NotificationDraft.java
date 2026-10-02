package com.fleetflow.notification.service;

import com.fleetflow.notification.entity.NotificationLevel;
import com.fleetflow.notification.entity.NotificationType;

/**
 * The content of a notification before it becomes a row. Bundling the fields keeps
 * the Kafka handlers readable: each one describes what the customer is told, not how
 * the row is assembled.
 *
 * <p>The recipient is not part of the draft because a single event may address more
 * than one user - a delivery assignment notifies the customer and the operations
 * desk.
 */
public record NotificationDraft(
        NotificationType type,
        NotificationLevel level,
        String title,
        String message,
        Long orderId,
        Long deliveryId) {
}
