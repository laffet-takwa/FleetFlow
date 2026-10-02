package com.fleetflow.common.event.payload;

import java.time.Instant;

import com.fleetflow.common.event.EventTypes;
import com.fleetflow.common.event.KafkaTopics;

/**
 * Emitted when a driver confirms a drop-off. This is the event that moves the
 * order to {@code DELIVERED} and produces the customer notification.
 *
 * @param deliveryId     delivery aggregate id
 * @param orderId        originating order id
 * @param customerId     customer who placed the order
 * @param driverId       Delivery Service local driver key
 * @param driverUserId   platform identity of the driver (JWT subject)
 * @param completedAt    when the driver confirmed the drop-off
 * @param proofOfDelivery optional free text such as "left with concierge"
 */
public record DeliveryCompletedPayload(
        Long deliveryId,
        Long orderId,
        Long customerId,
        Long driverId,
        Long driverUserId,
        Instant completedAt,
        String proofOfDelivery) {

    public static final String EVENT_TYPE = EventTypes.DELIVERY_COMPLETED;
    public static final String TOPIC = KafkaTopics.DELIVERY_COMPLETED;
}