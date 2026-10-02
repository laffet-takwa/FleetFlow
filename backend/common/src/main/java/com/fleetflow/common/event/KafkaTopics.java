package com.fleetflow.common.event;

/** Canonical Kafka topic names used across the platform. */
public final class KafkaTopics {

    /** Order Service -> Warehouse Service. Order accepted and stored. */
    public static final String ORDER_CREATED = "order.created";

    /** Warehouse Service -> Order Service / Delivery Service. Stock secured. */
    public static final String INVENTORY_RESERVED = "inventory.reserved";

    /** Warehouse Service -> Order Service. Reservation rejected. */
    public static final String INVENTORY_INSUFFICIENT = "inventory.insufficient";

    /** Delivery Service -> Notification Service. Driver and vehicle allocated. */
    public static final String DELIVERY_ASSIGNED = "delivery.assigned";

    /** Delivery Service -> Notification Service. Goods collected from warehouse. */
    public static final String DELIVERY_PICKED_UP = "delivery.picked-up";

    /** Delivery Service -> Tracking / Notification Service. Van is rolling. */
    public static final String DELIVERY_STARTED = "delivery.started";

    /** Delivery Service -> Order / Notification Service. Drop-off confirmed. */
    public static final String DELIVERY_COMPLETED = "delivery.completed";

    /** Delivery Service -> Order / Notification Service. Delivery attempt failed. */
    public static final String DELIVERY_FAILED = "delivery.failed";

    /** Delivery Service -> Order / Notification Service. Delivery cancelled. */
    public static final String DELIVERY_CANCELLED = "delivery.cancelled";

    private KafkaTopics() {
    }

    public static String[] all() {
        return new String[] {
                ORDER_CREATED, INVENTORY_RESERVED, INVENTORY_INSUFFICIENT,
                DELIVERY_ASSIGNED, DELIVERY_PICKED_UP, DELIVERY_STARTED,
                DELIVERY_COMPLETED, DELIVERY_FAILED, DELIVERY_CANCELLED
        };
    }
}