package com.fleetflow.order.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "OrderSummaryResponse", description = "Compact order projection for service-to-service reads")
public record OrderSummaryResponse(
        Long id,
        Long customerId,
        int itemCount,
        BigDecimal totalAmount,
        String status) {
}
