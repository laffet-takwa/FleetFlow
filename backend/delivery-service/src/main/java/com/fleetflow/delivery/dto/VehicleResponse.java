package com.fleetflow.delivery.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "VehicleResponse", description = "A vehicle together with its current driver")
public record VehicleResponse(
        Long id,
        String registrationNumber,
        @Schema(example = "VAN") String type,
        @Schema(description = "Payload limit in kilograms") Integer capacity,
        @Schema(example = "AVAILABLE") String status,
        Long driverId,
        String driverName,
        Long currentDeliveryId) {
}
