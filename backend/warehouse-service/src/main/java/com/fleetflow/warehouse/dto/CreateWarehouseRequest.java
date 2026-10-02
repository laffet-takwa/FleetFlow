package com.fleetflow.warehouse.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "CreateWarehouseRequest", description = "New fulfilment site")
public record CreateWarehouseRequest(
        @Schema(example = "Tunis Centre Warehouse")
        @NotBlank(message = "name is required")
        @Size(max = 120, message = "name must be at most 120 characters")
        String name,

        @Schema(example = "Zone Industrielle El Mghira")
        @NotBlank(message = "address is required")
        @Size(max = 255, message = "address must be at most 255 characters")
        String address,

        @Schema(example = "Tunis")
        @NotBlank(message = "city is required")
        @Size(max = 80, message = "city must be at most 80 characters")
        String city,

        @Schema(description = "Maximum units the site is designed to hold", example = "50000")
        @NotNull(message = "capacity is required")
        @Min(value = 1, message = "capacity must be greater than zero")
        Integer capacity) {
}