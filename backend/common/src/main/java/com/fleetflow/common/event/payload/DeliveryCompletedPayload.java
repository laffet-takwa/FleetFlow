package com.fleetflow.common.event.payload;

import java.time.Instant;

import com.fleetflow.common.event.EventTypes;
import com.fleetflow.common.event.KafkaTopics;

/**
 * Emitted when a driver confirms a drop-off. This is the event that moves the
 * order to {@code DELIVERED} and produces the customer notification.
 */
public record DeliveryCompletedPayload(
        Long deliveryId,
        Long orderId,
        Long customerId,
        Long driverId,
        Instant completedAt,
        String proofOfDelivery) {

    public static final String EVENT_TYPE = EventTypes.DELIVERY_COMPLETED;
    public static final String TOPIC = KafkaTopics.DELIVERY_COMPLETED;
}