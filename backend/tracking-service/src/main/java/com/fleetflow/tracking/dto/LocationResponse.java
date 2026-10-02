package com.fleetflow.tracking.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Canonical position shape, used for the ingestion response, the history feed, the value
 * cached in Redis and the payload of every SSE event, so all four stay wire compatible.
 */
@Schema(name = "LocationResponse", description = "A single stored position")
public record LocationResponse(
        @Schema(example = "3") Long deliveryId,
        @Schema(example = "3") Long driverId,
        @Schema(example = "9") Long customerId,
        @Schema(example = "36.8065") Double latitude,
        @Schema(example = "10.1815") Double longitude,
        @Schema(example = "42.5") Double speedKph,
        @Schema(example = "275.0") Double heading,
        @Schema(description = "When the device took the reading") Instant recordedAt,
        @Schema(description = "When the service accepted the reading") Instant receivedAt) {
}
