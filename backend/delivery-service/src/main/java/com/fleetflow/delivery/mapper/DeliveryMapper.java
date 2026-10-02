package com.fleetflow.delivery.mapper;

import org.springframework.stereotype.Component;

import com.fleetflow.delivery.dto.DeliveryResponse;
import com.fleetflow.delivery.entity.Delivery;
import com.fleetflow.delivery.entity.Driver;
import com.fleetflow.delivery.entity.Vehicle;

@Component
public class DeliveryMapper {

    public DeliveryResponse toResponse(Delivery delivery) {
        return toResponse(delivery, null, null);
    }

    /**
     * @param driver  the assigned driver, or {@code null} when the delivery has none
     * @param vehicle the assigned vehicle, or {@code null} when the delivery has none
     */
    public DeliveryResponse toResponse(Delivery delivery, Driver driver, Vehicle vehicle) {
        return new DeliveryResponse(
                delivery.getId(),
                delivery.getOrderId(),
                delivery.getCustomerId(),
                driver != null ? driver.getId() : delivery.getDriverId(),
                driver != null ? driver.getFullName() : null,
                vehicle != null ? vehicle.getId() : delivery.getVehicleId(),
                vehicle != null ? vehicle.getRegistrationNumber() : null,
                vehicle != null ? vehicle.getType().name() : null,
                delivery.getStatus().name(),
                delivery.getPickupAddress(),
                delivery.getDeliveryAddress(),
                delivery.getCity(),
                delivery.getPostalCode(),
                delivery.getCustomerName(),
                delivery.getCustomerPhone(),
                delivery.getFailureReason(),
                delivery.getProofOfDelivery(),
                delivery.getScheduledAt(),
                delivery.getStartedAt(),
                delivery.getCompletedAt(),
                delivery.getCreatedAt(),
                delivery.getUpdatedAt());
    }
}
