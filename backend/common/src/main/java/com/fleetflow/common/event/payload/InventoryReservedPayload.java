package com.fleetflow.common.event.payload;

import java.math.BigDecimal;

import com.fleetflow.common.event.EventTypes;
import com.fleetflow.common.event.KafkaTopics;

/** Emitted once the Warehouse Service has secured stock for every line of an order. */
public record InventoryReservedPayload(
        Long orderId,
        Long customerId,
        Long warehouseId,
        String warehouseName,
        BigDecimal reservedValue,
        int itemCount) {

    public static final String EVENT_TYPE = EventTypes.INVENTORY_RESERVED;
    public static final String TOPIC = KafkaTopics.INVENTORY_RESERVED;
}