package com.fleetflow.common.event.payload;

/**
 * Generic delivery lifecycle transition used for {@code delivery.picked-up},
 * {@code delivery.failed} and {@code delivery.cancelled}.
 *
 * <p>Each of those topics carries the same body; only the topic and the new status
 * differ, which keeps consumer code identical across the lifecycle.
 *
 * @param deliveryId     delivery aggregate id
 * @param orderId        originating order id
 * @param customerId     customer who placed the order
 * @param driverId       Delivery Service local driver key
 * @param driverUserId   platform identity of the driver (JWT subject), null before assignment
 * @param previousStatus status before the transition
 * @param newStatus      status after the transition
 * @param reason         optional human readable explanation, used for FAILED/CANCELLED
 */
public record DeliveryStatusChangedPayload(
        Long deliveryId,
        Long orderId,
        Long customerId,
        Long driverId,
        Long driverUserId,
        String previousStatus,
        String newStatus,
        String reason) {
}