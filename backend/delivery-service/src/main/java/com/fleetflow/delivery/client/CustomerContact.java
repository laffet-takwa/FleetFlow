package com.fleetflow.delivery.client;

import io.swagger.v3.oas.annotations.media.Schema;

/** Contact details returned by customer-service for a user id. */
@Schema(name = "CustomerContact", description = "Customer contact as held by customer-service")
public record CustomerContact(
        Long userId,
        String firstName,
        String lastName,
        String email,
        String phone,
        String address,
        String city,
        String postalCode) {
}
