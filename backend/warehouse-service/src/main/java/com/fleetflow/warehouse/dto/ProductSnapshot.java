package com.fleetflow.warehouse.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ProductSnapshot", description = "Minimal catalogue row for service-to-service reads")
public record ProductSnapshot(
        @Schema(example = "1") Long id,
        @Schema(example = "FF-EL-0001") String sku,
        @Schema(example = "Television Samsung 55 pouces 4K") String name,
        @Schema(description = "Unit price in TND", example = "1299.000") java.math.BigDecimal price,
        @Schema(example = "true") Boolean active,
        @Schema(example = "ELECTRONICS") String category) {
}