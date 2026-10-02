package com.fleetflow.delivery.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "UpdateDeliveryStatusRequest",
        description = "Target status plus the reason that doubles as the proof of delivery for DELIVERED")
public record UpdateDeliveryStatusRequest(
        @NotBlank @Schema(example = "IN_TRANSIT", allowableValues = {
                "CREATED", "ASSIGNED", "PICKED_UP", "IN_TRANSIT", "DELIVERED", "FAILED", "CANCELLED" })
        String status,
        @Schema(description = "Failure reason, cancellation reason or proof of delivery", example = "Handed to concierge")
        String reason) {
}
