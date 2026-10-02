package com.fleetflow.order.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "OrderItemResponse", description = "An ordered line, priced as it was at checkout")
public record OrderItemResponse(
        Long id,
        Long productId,
        String productName,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal lineSubtotal) {
}
