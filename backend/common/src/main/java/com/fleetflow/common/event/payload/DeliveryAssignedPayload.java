package com.fleetflow.common.event.payload;

import com.fleetflow.common.event.EventTypes;
import com.fleetflow.common.event.KafkaTopics;

/**
 * Emitted when Operations allocates a driver and a vehicle to a delivery.
 * The Notification Service uses the names to render a human readable message.
 */
public record DeliveryAssignedPayload(
        Long deliveryId,
        Long orderId,
        Long customerId,
        Long driverId,
        String driverName,
        Long vehicleId,
        String vehicleRegistration) {

    public static final String EVENT_TYPE = EventTypes.DELIVERY_ASSIGNED;
    public static final String TOPIC = KafkaTopics.DELIVERY_ASSIGNED;
}