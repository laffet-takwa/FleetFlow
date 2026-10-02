package com.fleetflow.common.event.payload;

import java.math.BigDecimal;

import com.fleetflow.common.event.EventTypes;
import com.fleetflow.common.event.KafkaTopics;

/**
 * Emitted by the Order Service once an order has been persisted.
 *
 * <p>Deliberately carries no customer PII: the Warehouse and Delivery services
 * only need identifiers and the fulfilment geography.
 */
public record OrderCreatedPayload(
        Long orderId,
        Long customerId,
        String city,
        String postalCode,
        int itemCount,
        BigDecimal totalAmount) {

    public static final String EVENT_TYPE = EventTypes.ORDER_CREATED;
    public static final String TOPIC = KafkaTopics.ORDER_CREATED;
}