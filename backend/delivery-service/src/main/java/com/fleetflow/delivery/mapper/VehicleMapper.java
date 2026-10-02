package com.fleetflow.delivery.mapper;

import org.springframework.stereotype.Component;

import com.fleetflow.delivery.dto.VehicleResponse;
import com.fleetflow.delivery.entity.Delivery;
import com.fleetflow.delivery.entity.Driver;
import com.fleetflow.delivery.entity.Vehicle;

@Component
public class VehicleMapper {

    /**
     * @param driver         the driver currently using the vehicle, or {@code null}
     * @param currentDelivery the open delivery this vehicle is on, or {@code null}
     */
    public VehicleResponse toResponse(Vehicle vehicle, Driver driver, Delivery currentDelivery) {
        return new VehicleResponse(
                vehicle.getId(),
                vehicle.getRegistrationNumber(),
                vehicle.getType().name(),
                vehicle.getCapacity(),
                vehicle.getStatus().name(),
                driver != null ? driver.getId() : null,
                driver != null ? driver.getFullName() : null,
                currentDelivery != null ? currentDelivery.getId() : null);
    }
}
