package com.fleetflow.order.entity;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The order lifecycle, declared once so the API, the Kafka consumers and the seed
 * data can never disagree about what a legal transition is.
 *
 * <p>Terminal states simply have an empty target set, which is also what makes
 * {@link #isTerminal()} free of any second list to keep in sync.
 */
public enum OrderStatus {

    CREATED,
    CONFIRMED,
    PROCESSING,
    READY_FOR_DELIVERY,
    OUT_FOR_DELIVERY,
    DELIVERED,
    CANCELLED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS = new EnumMap<>(OrderStatus.class);

    // A static initialiser runs after the constants exist, so the constants can be
    // referenced here directly.
    static {
        ALLOWED_TRANSITIONS.put(CREATED, EnumSet.of(CONFIRMED, CANCELLED));
        ALLOWED_TRANSITIONS.put(CONFIRMED, EnumSet.of(PROCESSING, CANCELLED));
        ALLOWED_TRANSITIONS.put(PROCESSING, EnumSet.of(READY_FOR_DELIVERY, CANCELLED));
        ALLOWED_TRANSITIONS.put(READY_FOR_DELIVERY, EnumSet.of(OUT_FOR_DELIVERY));
        ALLOWED_TRANSITIONS.put(OUT_FOR_DELIVERY, EnumSet.of(DELIVERED));
        ALLOWED_TRANSITIONS.put(DELIVERED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED_TRANSITIONS.put(CANCELLED, EnumSet.noneOf(OrderStatus.class));
    }

    public boolean canTransitionTo(OrderStatus target) {
        return target != null && targets().contains(target);
    }

    public boolean isTerminal() {
        return targets().isEmpty();
    }

    public Set<OrderStatus> allowedTargets() {
        return Collections.unmodifiableSet(targets());
    }

    private Set<OrderStatus> targets() {
        return ALLOWED_TRANSITIONS.getOrDefault(this, Set.of());
    }
}
