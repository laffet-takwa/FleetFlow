package com.fleetflow.warehouse.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "AdjustInventoryRequest", description = "Signed correction applied to one stock level")
public record AdjustInventoryRequest(
        @Schema(description = "Added to available quantity; may be negative, a resulting level below zero is a 409",
                example = "25")
        @NotNull(message = "quantityDelta is required")
        Integer quantityDelta,

        @Schema(description = "Why the correction was made; kept for the operations trail", example = "Recount after delivery")
        @Size(max = 255, message = "reason must be at most 255 characters")
        String reason) {
}