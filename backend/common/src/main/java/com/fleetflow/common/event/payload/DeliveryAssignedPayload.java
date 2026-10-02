package com.fleetflow.common.event.payload;

import com.fleetflow.common.event.EventTypes;
import com.fleetflow.common.event.KafkaTopics;

/**
 * Emitted when Operations allocates a driver and a vehicle to a delivery.
 * The Notification Service uses the names to render a human readable message.
 *
 * <p>Two driver identifiers travel with this event and they are not interchangeable:
 * {@code driverId} is the Delivery Service's own primary key, while
 * {@code driverUserId} is the platform wide identity (the JWT subject issued by the
 * Auth Service). Any service that has to decide "is this the caller?" must compare
 * against {@code driverUserId}, never against the local key.
 *
 * @param deliveryId           delivery aggregate id
 * @param orderId              originating order id
 * @param customerId           customer who placed the order
 * @param driverId             Delivery Service local driver key
 * @param driverUserId         platform identity of the driver (JWT subject)
 * @param driverName           display name, for notifications
 * @param vehicleId            vehicle key
 * @param vehicleRegistration  vehicle plate, for notifications
 */
public record DeliveryAssignedPayload(
        Long deliveryId,
        Long orderId,
        Long customerId,
        Long driverId,
        Long driverUserId,
        String driverName,
        Long vehicleId,
        String vehicleRegistration) {

    public static final String EVENT_TYPE = EventTypes.DELIVERY_ASSIGNED;
    public static final String TOPIC = KafkaTopics.DELIVERY_ASSIGNED;
}