package com.fleetflow.warehouse.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "CreateProductRequest", description = "New catalogue entry")
public record CreateProductRequest(
        @Schema(example = "FF-EL-0001")
        @NotBlank(message = "sku is required")
        @Size(max = 48, message = "sku must be at most 48 characters")
        String sku,

        @Schema(example = "Television Samsung 55 pouces 4K")
        @NotBlank(message = "name is required")
        @Size(max = 160, message = "name must be at most 160 characters")
        String name,

        @Schema(example = "Smart TV 4K HDR avec assistant vocal")
        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        @Schema(description = "One of GROCERY, ELECTRONICS, HOME, BEAUTY, SPORTS, STATIONERY", example = "ELECTRONICS")
        @NotBlank(message = "category is required")
        @Size(max = 60, message = "category must be at most 60 characters")
        String category,

        @Schema(description = "Unit price in TND", example = "1299.000")
        @NotNull(message = "price is required")
        @DecimalMin(value = "0.000", message = "price must not be negative")
        @Digits(integer = 9, fraction = 3, message = "price must have at most 3 decimal places")
        BigDecimal price,

        @Schema(description = "Defaults to true when omitted", example = "true")
        Boolean active) {
}