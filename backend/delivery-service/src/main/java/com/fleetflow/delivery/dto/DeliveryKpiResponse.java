package com.fleetflow.delivery.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "DeliveryKpiResponse", description = "Headline counters for the operations dashboard")
public record DeliveryKpiResponse(
        long activeDeliveries,
        long availableDrivers,
        long busyDrivers,
        long availableVehicles,
        long completedToday,
        long failedDeliveries) {
}
