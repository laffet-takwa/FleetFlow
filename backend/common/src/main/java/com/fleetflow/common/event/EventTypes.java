package com.fleetflow.common.event;

/** Logical event type constants, carried inside {@link EventEnvelope#eventType()}. */
public final class EventTypes {

    public static final String ORDER_CREATED = "ORDER_CREATED";
    public static final String INVENTORY_RESERVED = "INVENTORY_RESERVED";
    public static final String INVENTORY_INSUFFICIENT = "INVENTORY_INSUFFICIENT";
    public static final String DELIVERY_ASSIGNED = "DELIVERY_ASSIGNED";
    public static final String DELIVERY_PICKED_UP = "DELIVERY_PICKED_UP";
    public static final String DELIVERY_STARTED = "DELIVERY_STARTED";
    public static final String DELIVERY_COMPLETED = "DELIVERY_COMPLETED";
    public static final String DELIVERY_FAILED = "DELIVERY_FAILED";
    public static final String DELIVERY_CANCELLED = "DELIVERY_CANCELLED";

    private EventTypes() {
    }
}