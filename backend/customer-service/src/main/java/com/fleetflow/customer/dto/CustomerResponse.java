package com.fleetflow.customer.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CustomerResponse", description = "A customer profile")
public record CustomerResponse(
        @Schema(example = "8") Long id,
        @Schema(description = "auth-service user id this profile belongs to", example = "8") Long userId,
        @Schema(example = "Yasmine") String firstName,
        @Schema(example = "Ben Salah") String lastName,
        @Schema(example = "customer1@fleetflow.local") String email,
        @Schema(example = "+21620100101") String phone,
        @Schema(example = "12 Rue Habib Bourguiba") String address,
        @Schema(example = "Tunis") String city,
        @Schema(example = "1000") String postalCode,
        @Schema(example = "2026-10-02T13:53:11.204Z") Instant createdAt,
        @Schema(example = "2026-10-02T13:53:11.204Z") Instant updatedAt) {
}
