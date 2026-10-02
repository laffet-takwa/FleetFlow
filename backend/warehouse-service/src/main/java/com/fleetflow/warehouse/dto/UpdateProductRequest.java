package com.fleetflow.warehouse.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

/**
 * Partial catalogue edit: every field is optional and a null means "leave as is".
 *
 * <p>The SKU is deliberately absent. It is the business key that order lines, the
 * inventory seed and external references quote, so changing it is a new product
 * rather than an edit.
 */
@Schema(name = "UpdateProductRequest", description = "Partial catalogue edit; omitted fields are left alone")
public record UpdateProductRequest(
        @Schema(example = "Television Samsung 55 pouces 4K UHD")
        @Size(max = 160, message = "name must be at most 160 characters")
        String name,

        @Schema(example = "Smart TV 4K HDR avec assistant vocal")
        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        @Schema(example = "ELECTRONICS")
        @Size(max = 60, message = "category must be at most 60 characters")
        String category,

        @Schema(description = "Unit price in TND", example = "1349.000")
        @DecimalMin(value = "0.000", message = "price must not be negative")
        @Digits(integer = 9, fraction = 3, message = "price must have at most 3 decimal places")
        BigDecimal price,

        @Schema(description = "Set false to retire the product from checkout", example = "false")
        Boolean active) {
}