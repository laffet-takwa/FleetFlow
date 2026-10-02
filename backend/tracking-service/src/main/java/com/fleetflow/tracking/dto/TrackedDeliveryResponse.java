package com.fleetflow.tracking.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Delivery summary shown on the live map and in the tracking list, with the newest known
 * position already resolved.
 */
@Schema(name = "TrackedDeliveryResponse", description = "A delivery being tracked")
public record TrackedDeliveryResponse(
        @Schema(example = "3") Long deliveryId,
        @Schema(example = "3") Long orderId,
        @Schema(example = "9") Long customerId,
        @Schema(example = "3") Long driverId,
        @Schema(example = "Yassine Ben Salah") String driverName,
        @Schema(example = "IN_TRANSIT") String status,
        @Schema(example = "12 Rue de la Liberté, La Marsa") String destination,
        @Schema(example = "La Marsa") String city,
        @Schema(description = "Newest position, absent when the delivery has not reported yet")
        LocationResponse latestLocation,
        @Schema(example = "42") int locationCount,
        @Schema(description = "True while the last fix is younger than the configured staleness window")
        boolean online,
        Instant lastLocationAt) {
}
