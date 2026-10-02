package com.fleetflow.delivery.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "DeliveryResponse", description = "A delivery with its current driver and vehicle")
public record DeliveryResponse(
        @Schema(example = "12") Long id,
        @Schema(example = "42") Long orderId,
        @Schema(example = "8") Long customerId,
        @Schema(example = "3") Long driverId,
        @Schema(description = "Platform identity of the driver (JWT subject), so a driver can key on their "
                + "own rows; null while the delivery is unassigned") Long driverUserId,
        @Schema(description = "Resolved from the driver record so lists need no extra lookup") String driverName,
        @Schema(example = "7") Long vehicleId,
        String vehicleRegistration,
        @Schema(example = "VAN") String vehicleType,
        @Schema(example = "ASSIGNED") String status,
        String pickupAddress,
        String deliveryAddress,
        String city,
        String postalCode,
        String customerName,
        String customerPhone,
        String failureReason,
        String proofOfDelivery,
        Instant scheduledAt,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt) {
}
