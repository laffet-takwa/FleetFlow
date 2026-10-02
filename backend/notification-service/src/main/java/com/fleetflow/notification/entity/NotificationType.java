package com.fleetflow.notification.entity;

/** The business trigger that produced a notification. */
public enum NotificationType {
    ORDER_CONFIRMED,
    INVENTORY_RESERVED,
    INVENTORY_INSUFFICIENT,
    DRIVER_ASSIGNED,
    DELIVERY_STARTED,
    DELIVERY_COMPLETED,
    DELIVERY_FAILED,
    DELIVERY_CANCELLED,
    ORDER_CANCELLED
}
