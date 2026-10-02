package com.fleetflow.delivery.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(name = "AssignDeliveryRequest", description = "Driver and vehicle allocated to a delivery")
public record AssignDeliveryRequest(
        @NotNull @Schema(example = "3") Long driverId,
        @NotNull @Schema(example = "7") Long vehicleId) {
}
