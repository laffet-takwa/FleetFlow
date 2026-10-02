package com.fleetflow.warehouse.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "InventoryResponse", description = "A stock level at one warehouse")
public record InventoryResponse(
        @Schema(example = "42") Long id,
        @Schema(example = "1") Long warehouseId,
        @Schema(example = "Tunis Centre Warehouse") String warehouseName,
        @Schema(example = "1") Long productId,
        @Schema(example = "FF-EL-0001") String productSku,
        @Schema(example = "Television Samsung 55 pouces 4K") String productName,
        @Schema(example = "ELECTRONICS") String category,
        @Schema(example = "42") Integer availableQuantity,
        @Schema(example = "8") Integer reservedQuantity,
        @Schema(description = "Derived from availableQuantity; see StockStatusResolver", example = "IN_STOCK")
        String stockStatus,
        @Schema(example = "2026-10-02T09:15:00Z") Instant updatedAt) {
}