package com.fleetflow.tracking.document;

/**
 * Delivery lifecycle values this service reacts to. The delivery service owns the state
 * machine; tracking only needs to know which statuses stop a stream and which end it.
 */
public final class DeliveryStatus {

    public static final String ASSIGNED = "ASSIGNED";
    public static final String PICKED_UP = "PICKED_UP";
    public static final String IN_TRANSIT = "IN_TRANSIT";
    public static final String CREATED = "CREATED";
    public static final String DELIVERED = "DELIVERED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    private DeliveryStatus() {
    }

    public static boolean isTerminal(String status) {
        return DELIVERED.equals(status) || CANCELLED.equals(status) || FAILED.equals(status);
    }
}
