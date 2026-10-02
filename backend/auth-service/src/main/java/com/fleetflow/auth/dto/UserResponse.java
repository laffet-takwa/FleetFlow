package com.fleetflow.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** The public projection of a user. No credential material is ever part of it. */
@Schema(name = "UserResponse", description = "A FleetFlow user")
public record UserResponse(

        @Schema(example = "8") Long id,
        @Schema(example = "Ahmed") String firstName,
        @Schema(example = "Ben Ali") String lastName,
        @Schema(example = "customer1@fleetflow.local") String email,
        @Schema(example = "+21620123456") String phone,
        @Schema(description = "ADMIN, OPERATIONS, DRIVER or CUSTOMER", example = "CUSTOMER") String role,
        @Schema(example = "true") boolean enabled) {
}