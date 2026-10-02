package com.fleetflow.delivery.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(name = "DriverStatusRequest", description = "A driver declaring their own availability")
public record DriverStatusRequest(
        @NotNull @Schema(example = "AVAILABLE", allowableValues = { "AVAILABLE", "ON_DELIVERY", "OFFLINE" })
        String status) {
}
