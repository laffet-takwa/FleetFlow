package com.fleetflow.warehouse.dto;

import com.fleetflow.warehouse.entity.WarehouseStatus;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** Partial warehouse edit; a null field means "leave as is". */
@Schema(name = "UpdateWarehouseRequest", description = "Partial warehouse edit; omitted fields are left alone")
public record UpdateWarehouseRequest(
        @Schema(example = "Tunis Centre Warehouse")
        @Size(max = 120, message = "name must be at most 120 characters")
        String name,

        @Schema(example = "Zone Industrielle El Mghira")
        @Size(max = 255, message = "address must be at most 255 characters")
        String address,

        @Schema(example = "Tunis")
        @Size(max = 80, message = "city must be at most 80 characters")
        String city,

        @Schema(example = "60000")
        @Min(value = 1, message = "capacity must be greater than zero")
        Integer capacity,

        @Schema(description = "Set INACTIVE to close the site to new reservations", example = "INACTIVE")
        WarehouseStatus status) {
}