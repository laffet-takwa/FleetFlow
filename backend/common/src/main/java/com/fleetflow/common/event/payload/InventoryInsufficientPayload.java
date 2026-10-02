package com.fleetflow.common.event.payload;

import java.util.List;

import com.fleetflow.common.event.EventTypes;
import com.fleetflow.common.event.KafkaTopics;

/**
 * Emitted when the Warehouse Service cannot satisfy an order.
 *
 * <p>The Order Service reacts by cancelling the order and the Notification Service
 * explains to the customer exactly which product was short.
 */
public record InventoryInsufficientPayload(
        Long orderId,
        Long customerId,
        List<Shortfall> shortfalls) {

    public static final String EVENT_TYPE = EventTypes.INVENTORY_INSUFFICIENT;
    public static final String TOPIC = KafkaTopics.INVENTORY_INSUFFICIENT;

    /**
     * @param productId    product that could not be fully reserved
     * @param productName  display name of the product
     * @param requested    quantity the customer asked for
     * @param available    quantity actually available in the warehouse
     */
    public record Shortfall(
            Long productId,
            String productName,
            int requested,
            int available) {
    }
}