package com.fleetflow.warehouse.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A stock level that needs attention, as returned by the low-stock endpoint.
 *
 * <p>Field-for-field the same as {@link InventoryResponse} so an operator view can
 * render one row component for both; it is a separate type because the two lists are
 * never interchangeable by accident.
 */
@Schema(name = "LowStockResponse", description = "A stock level at or below the low-stock threshold")
public record LowStockResponse(
        @Schema(example = "42") Long id,
        @Schema(example = "1") Long warehouseId,
        @Schema(example = "Tunis Centre Warehouse") String warehouseName,
        @Schema(example = "7") Long productId,
        @Schema(example = "FF-GR-0007") String productSku,
        @Schema(example = "Huile d'olive extra vierge 1L") String productName,
        @Schema(example = "GROCERY") String category,
        @Schema(description = "1..10 for LOW_STOCK, 0 for OUT_OF_STOCK", example = "4") Integer availableQuantity,
        @Schema(example = "0") Integer reservedQuantity,
        @Schema(description = "LOW_STOCK or OUT_OF_STOCK", example = "LOW_STOCK") String stockStatus,
        @Schema(example = "2026-10-02T09:15:00Z") Instant updatedAt) {
}