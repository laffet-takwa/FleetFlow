package com.fleetflow.order.entity;

/**
 * Who caused a status change. {@code SYSTEM} covers every transition driven by a
 * Kafka event, so the timeline can distinguish a human decision from automation.
 */
public enum StatusSource {

    CUSTOMER,
    OPERATIONS,
    SYSTEM
}
