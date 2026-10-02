package com.fleetflow.warehouse.entity;

/**
 * How a stock level reads to a human.
 *
 * <p>Never persisted: the value is derived from {@code available_quantity} on every
 * read, so a level can never disagree with the badge drawn next to it. The rule lives
 * in {@link com.fleetflow.warehouse.service.StockStatusResolver}.
 */
public enum StockStatus {
    IN_STOCK,
    LOW_STOCK,
    OUT_OF_STOCK
}