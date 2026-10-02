package com.fleetflow.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "DailyOrderCount", description = "One calendar day of the order volume series")
public record DailyOrderCount(
        @Schema(description = "ISO date, always present even when the day had no activity", example = "2026-09-28")
        String date,
        long orders,
        long delivered,
        long cancelled) {
}
