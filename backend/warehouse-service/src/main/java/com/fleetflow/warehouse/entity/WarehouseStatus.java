package com.fleetflow.warehouse.entity;

/** Lifecycle of a warehouse. An inactive site is closed to new reservations. */
public enum WarehouseStatus {
    ACTIVE,
    INACTIVE
}