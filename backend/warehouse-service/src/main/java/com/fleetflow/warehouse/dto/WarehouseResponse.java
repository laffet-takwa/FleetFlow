package com.fleetflow.warehouse.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "WarehouseResponse", description = "A fulfilment site and how much it holds")
public record WarehouseResponse(
        @Schema(example = "1") Long id,
        @Schema(example = "Tunis Centre Warehouse") String name,
        @Schema(example = "Zone Industrielle El Mghira") String address,
        @Schema(example = "Tunis") String city,
        @Schema(description = "Maximum units the site is designed to hold", example = "50000") Integer capacity,
        @Schema(description = "ACTIVE or INACTIVE", example = "ACTIVE") String status,
        @Schema(description = "Distinct products currently stocked here", example = "20") Long distinctProductCount) {
}