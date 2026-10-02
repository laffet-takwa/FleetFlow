package com.fleetflow.delivery.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "DeliveryCustomerContact",
        description = "Drop-off contact for the assigned driver, served from the snapshot stored on the delivery")
public record DeliveryCustomerContact(
        Long deliveryId,
        Long orderId,
        String customerName,
        String customerPhone,
        @Schema(description = "Not stored locally, so it is omitted unless a caller has already supplied it")
        String customerEmail,
        String deliveryAddress,
        String city,
        String postalCode) {
}
