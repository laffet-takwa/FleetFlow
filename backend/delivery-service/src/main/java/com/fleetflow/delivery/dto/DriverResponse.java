package com.fleetflow.delivery.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "DriverResponse", description = "A driver together with what they are doing right now")
public record DriverResponse(
        Long id,
        @Schema(description = "auth-service user id, the JWT subject of this driver") Long userId,
        String fullName,
        String licenseNumber,
        String phone,
        @Schema(example = "AVAILABLE") String status,
        Long currentDeliveryId,
        String currentDeliveryStatus,
        @Schema(description = "Deliveries already completed by this driver") Long completedDeliveries,
        @Schema(description = "Registration of the vehicle on the current delivery") String vehicleRegistration) {
}
