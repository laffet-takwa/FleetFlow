package com.fleetflow.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of the internal call that lets customer-service pre-create a profile, so the
 * delivery address captured at registration is not lost between the two stores.
 */
@Schema(name = "CustomerProfilePreCreateRequest",
        description = "New customer profile derived from a freshly registered account")
public record CustomerProfilePreCreateRequest(
        @Schema(example = "18") Long userId,
        @Schema(example = "Sonia") String firstName,
        @Schema(example = "Trabelsi") String lastName,
        @Schema(example = "sonia.trabelsi@example.com") String email,
        @Schema(example = "+21620123456") String phone,
        @Schema(example = "12 rue des Palmiers, Tunis") String address) {
}