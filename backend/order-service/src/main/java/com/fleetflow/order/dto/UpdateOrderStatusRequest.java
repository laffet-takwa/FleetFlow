package com.fleetflow.order.dto;

import com.fleetflow.order.entity.OrderStatus;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "UpdateOrderStatusRequest", description = "Manual lifecycle step, staff only")
public record UpdateOrderStatusRequest(

        @Schema(description = "Target status; must be reachable from the current one", example = "PROCESSING")
        @NotNull(message = "status is required")
        OrderStatus status,

        @Schema(example = "Picked and packed at the Tunis hub")
        @Size(max = 255, message = "note must not exceed 255 characters")
        String note) {
}
