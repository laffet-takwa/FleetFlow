package com.fleetflow.warehouse.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ProductResponse", description = "A catalogue entry with its stock reading at the preferred warehouse")
public record ProductResponse(
        @Schema(example = "1") Long id,
        @Schema(example = "FF-EL-0001") String sku,
        @Schema(example = "Television Samsung 55 pouces 4K") String name,
        @Schema(description = "Free text shown on the product page") String description,
        @Schema(example = "ELECTRONICS") String category,
        @Schema(description = "Unit price in TND, three decimals", example = "1299.000") BigDecimal price,
        @Schema(description = "An inactive product is hidden from checkout") Boolean active,
        @Schema(description = "Units free to sell at the preferred warehouse", example = "42") Integer availableQuantity,
        @Schema(description = "Units already committed to open orders", example = "8") Integer reservedQuantity,
        @Schema(description = "Derived from availableQuantity; see StockStatusResolver", example = "IN_STOCK")
        String stockStatus) {
}