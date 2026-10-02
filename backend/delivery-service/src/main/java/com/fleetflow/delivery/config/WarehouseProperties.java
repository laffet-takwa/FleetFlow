package com.fleetflow.delivery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Pickup point used when a delivery is opened from {@code inventory.reserved}. The
 * event carries the warehouse id and name but not its address, and the delivery
 * column is not nullable, so the platform default is applied.
 */
@ConfigurationProperties(prefix = "fleetflow.warehouses")
public class WarehouseProperties {

    private String defaultPickupAddress = "Zone Industrielle El Mghira";
    private String defaultPickupCity = "Tunis";
    private String defaultPickupPostalCode = "1000";

    public String getDefaultPickupAddress() {
        return defaultPickupAddress;
    }

    public void setDefaultPickupAddress(String defaultPickupAddress) {
        this.defaultPickupAddress = defaultPickupAddress;
    }

    public String getDefaultPickupCity() {
        return defaultPickupCity;
    }

    public void setDefaultPickupCity(String defaultPickupCity) {
        this.defaultPickupCity = defaultPickupCity;
    }

    public String getDefaultPickupPostalCode() {
        return defaultPickupPostalCode;
    }

    public void setDefaultPickupPostalCode(String defaultPickupPostalCode) {
        this.defaultPickupPostalCode = defaultPickupPostalCode;
    }
}
