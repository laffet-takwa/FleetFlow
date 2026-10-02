package com.fleetflow.delivery.entity;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;

/**
 * Delivery lifecycle. The transition table below is the only place in the service
 * where a lifecycle edge is defined; everything else asks
 * {@link #canTransitionTo(DeliveryStatus)}.
 */
public enum DeliveryStatus {

    /** Created from {@code inventory.reserved}, waiting for operations to allocate a driver and vehicle. */
    CREATED,
    /** Driver and vehicle allocated, driver has not reached the pickup point yet. */
    ASSIGNED,
    /** Goods collected at the warehouse. */
    PICKED_UP,
    /** On the road to the customer. */
    IN_TRANSIT,
    /** Drop-off confirmed, terminal. */
    DELIVERED,
    /** Attempt abandoned, terminal for the driver; staff may requeue it. */
    FAILED,
    /** Cancelled before completion, terminal. */
    CANCELLED;

    /**
     * Deliveries that still occupy a driver and a vehicle. {@link #FAILED} is
     * excluded on purpose: a failed attempt releases both resources, otherwise the
     * driver could never be given another delivery.
     */
    public static final Set<DeliveryStatus> OPEN = EnumSet.of(CREATED, ASSIGNED, PICKED_UP, IN_TRANSIT);

    private static final Map<DeliveryStatus, Set<DeliveryStatus>> ALLOWED = Map.of(
            CREATED, EnumSet.of(ASSIGNED, CANCELLED),
            ASSIGNED, EnumSet.of(PICKED_UP, CANCELLED),
            PICKED_UP, EnumSet.of(IN_TRANSIT, FAILED),
            IN_TRANSIT, EnumSet.of(DELIVERED, FAILED),
            FAILED, EnumSet.of(ASSIGNED),
            DELIVERED, EnumSet.noneOf(DeliveryStatus.class),
            CANCELLED, EnumSet.noneOf(DeliveryStatus.class));

    public boolean canTransitionTo(DeliveryStatus target) {
        return target != null && ALLOWED.get(this).contains(target);
    }

    /** No further lifecycle change is possible without staff intervention. */
    public boolean isTerminal() {
        return !OPEN.contains(this);
    }

    public boolean isOpen() {
        return OPEN.contains(this);
    }

    public static DeliveryStatus from(String value) {
        if (value != null) {
            for (DeliveryStatus status : values()) {
                if (status.name().equalsIgnoreCase(value.trim())) {
                    return status;
                }
            }
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "Unknown delivery status '" + value + "'");
    }
}
