package com.fleetflow.warehouse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Which warehouse fulfils orders.
 *
 * <p>Kept in configuration so the platform can be pointed at a different site per
 * environment without a rebuild, and so reservation reads the id from exactly one
 * place.
 */
@Component
@ConfigurationProperties(prefix = "fleetflow.reservation")
public class ReservationProperties {

    /** Matches the seeded Tunis warehouse, which is also the demo's fulfilment site. */
    private Long preferredWarehouseId = 1L;

    public Long getPreferredWarehouseId() {
        return preferredWarehouseId;
    }

    public void setPreferredWarehouseId(Long preferredWarehouseId) {
        this.preferredWarehouseId = preferredWarehouseId;
    }
}