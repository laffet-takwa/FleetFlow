package com.fleetflow.delivery.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Body of the two endpoints whose target status is implied by the route itself: cancel
 * always means {@code CANCELLED} and requeue always means {@code FAILED -> ASSIGNED}.
 * Carrying a {@code status} field there would invite a caller to believe they choose it.
 *
 * <p>{@code reason} is bounded to the width of {@code deliveries.failure_reason}, which is
 * what an unbounded string would overflow and have the database refuse.
 */
@Schema(name = "DeliveryReasonRequest", description = "Optional reason for cancelling or requeueing a delivery")
public record DeliveryReasonRequest(
        @Size(max = 255, message = "reason must not exceed 255 characters")
        @Schema(description = "Why the delivery is being cancelled or requeued", example = "Customer changed their mind")
        String reason) {
}