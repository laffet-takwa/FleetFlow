package com.fleetflow.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Reservation input for the warehouse service: identifiers and quantities only,
 * because reservation needs no pricing and no customer detail.
 */
@Schema(name = "OrderItemLineResponse", description = "Product id and quantity, for warehouse reservation")
public record OrderItemLineResponse(
        Long productId,
        Integer quantity) {
}
