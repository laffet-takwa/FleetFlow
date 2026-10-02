package com.fleetflow.tracking.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * A position report posted by a driver's device. The bean validation below is only the
 * first line of defence: the ingestion service re-checks the bounds against the configured
 * maxima so a caller bypassing the web layer cannot write nonsense coordinates.
 */
@Schema(name = "LocationUpdateRequest", description = "GPS fix reported for a delivery")
public record LocationUpdateRequest(
        @Schema(example = "3") @NotNull(message = "deliveryId is required")
        Long deliveryId,

        @Schema(example = "36.8065") @NotNull(message = "latitude is required")
        @DecimalMin(value = "-90", message = "latitude must be between -90 and 90")
        @DecimalMax(value = "90", message = "latitude must be between -90 and 90")
        Double latitude,

        @Schema(example = "10.1815") @NotNull(message = "longitude is required")
        @DecimalMin(value = "-180", message = "longitude must be between -180 and 180")
        @DecimalMax(value = "180", message = "longitude must be between -180 and 180")
        Double longitude,

        @Schema(example = "42.5", description = "Ground speed in km/h; omitted when GPS has no fix")
        Double speedKph,

        @Schema(example = "275.0", description = "Direction of travel in degrees clockwise from north")
        Double heading,

        @Schema(example = "2026-10-02T14:05:00Z", description = "When the device took the reading")
        Instant recordedAt) {
}
