package com.fleetflow.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "OrderResponse", description = "A complete order, items and timeline included")
public record OrderResponse(
        Long id,
        Long customerId,
        String status,
        BigDecimal subtotal,
        BigDecimal deliveryFee,
        BigDecimal totalAmount,
        String currency,
        @Schema(description = "Sum of the line quantities") int itemCount,
        String deliveryAddress,
        String city,
        String postalCode,
        Long deliveryId,
        String cancelledReason,
        Instant createdAt,
        Instant updatedAt,
        List<OrderItemResponse> items,
        List<OrderTimelineEntry> timeline) {
}
