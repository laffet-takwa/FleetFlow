package com.fleetflow.delivery.repository;

/** Aggregate row of {@code count(delivery) grouped by driver} used to enrich driver listings. */
public interface DriverDeliveryCount {

    Long getDriverId();

    Long getTotal();
}
