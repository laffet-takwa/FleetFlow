package com.fleetflow.order.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "OrderTimelineEntry", description = "One status change, as rendered by the order details timeline")
public record OrderTimelineEntry(
        String status,
        String previousStatus,
        String source,
        String note,
        Instant changedAt) {
}
