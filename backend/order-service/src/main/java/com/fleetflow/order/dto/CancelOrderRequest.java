package com.fleetflow.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(name = "CancelOrderRequest", description = "Optional cancellation justification")
public record CancelOrderRequest(

        @Schema(example = "Ordered the wrong quantity")
        @Size(max = 255, message = "reason must not exceed 255 characters")
        String reason) {
}
