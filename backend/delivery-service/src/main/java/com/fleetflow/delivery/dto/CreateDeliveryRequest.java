package com.fleetflow.delivery.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "CreateDeliveryRequest", description = "Manual creation for an order that has no delivery yet")
public record CreateDeliveryRequest(
        @NotNull @Schema(example = "42") Long orderId,
        @NotNull @Schema(example = "8") Long customerId,
        @NotBlank @Size(max = 255) String pickupAddress,
        @NotBlank @Size(max = 255) String deliveryAddress,
        @NotBlank @Size(max = 80) String city,
        @NotBlank @Size(max = 16) String postalCode,
        @Size(max = 120) String customerName,
        @Size(max = 32) String customerPhone,
        Instant scheduledAt) {
}
