package com.fleetflow.warehouse.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** How much stock the platform is sitting on, counted across every warehouse. */
@Schema(name = "StockSummary", description = "Stock counts grouped by derived stock status")
public record StockSummary(
        @Schema(description = "Levels above the low-stock threshold", example = "17") Long inStock,
        @Schema(description = "Levels from 1 up to the threshold", example = "3") Long lowStock,
        @Schema(description = "Levels at zero", example = "1") Long outOfStock,
        @Schema(description = "Distinct products stocked anywhere", example = "20") Long totalProducts) {
}