package com.fleetflow.delivery.mapper;

import org.springframework.stereotype.Component;

import com.fleetflow.delivery.dto.DriverResponse;
import com.fleetflow.delivery.entity.Delivery;
import com.fleetflow.delivery.entity.Driver;

@Component
public class DriverMapper {

    /**
     * @param currentDelivery       the open delivery of this driver, or {@code null}
     * @param completedDeliveries   deliveries this driver already finished
     * @param vehicleRegistration   registration of the vehicle on {@code currentDelivery}
     */
    public DriverResponse toResponse(Driver driver, Delivery currentDelivery, long completedDeliveries,
            String vehicleRegistration) {

        return new DriverResponse(
                driver.getId(),
                driver.getUserId(),
                driver.getFullName(),
                driver.getLicenseNumber(),
                driver.getPhone(),
                driver.getStatus().name(),
                currentDelivery != null ? currentDelivery.getId() : null,
                currentDelivery != null ? currentDelivery.getStatus().name() : null,
                completedDeliveries,
                vehicleRegistration);
    }
}
