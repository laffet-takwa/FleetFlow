package com.fleetflow.tracking.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Ordered trail for one delivery. Oldest first so a map polyline can be drawn by walking
 * the list once, which is the order the browser replays it in.
 */
@Schema(name = "TrackingHistoryResponse", description = "Recorded trail of a delivery")
public record TrackingHistoryResponse(
        @Schema(example = "3") Long deliveryId,
        @Schema(example = "37") int count,
        @Schema(description = "Positions ordered oldest to newest") List<LocationResponse> locations) {
}
