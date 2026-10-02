package com.fleetflow.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What the delivery service needs before a delivery starts. It is keyed by the
 * auth-service user id because the caller holds an order, not a customer row id.
 */
@Schema(name = "CustomerContactResponse", description = "Delivery-facing contact details of a customer")
public record CustomerContactResponse(
        @Schema(description = "auth-service user id this profile belongs to", example = "8") Long userId,
        @Schema(example = "Yasmine") String firstName,
        @Schema(example = "Ben Salah") String lastName,
        @Schema(example = "customer1@fleetflow.local") String email,
        @Schema(example = "+21620100101") String phone,
        @Schema(example = "12 Rue Habib Bourguiba") String address,
        @Schema(example = "Tunis") String city,
        @Schema(example = "1000") String postalCode) {
}
