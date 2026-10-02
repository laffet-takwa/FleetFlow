package com.fleetflow.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "OrderKpiResponse", description = "Order counts by lifecycle bucket, for the operations dashboard")
public record OrderKpiResponse(
        long totalOrders,
        @Schema(description = "CREATED, CONFIRMED, PROCESSING, READY_FOR_DELIVERY or OUT_FOR_DELIVERY")
        long activeOrders,
        long deliveredOrders,
        @Schema(description = "CREATED or CONFIRMED, i.e. not yet being fulfilled")
        long pendingOrders,
        long cancelledOrders) {
}
