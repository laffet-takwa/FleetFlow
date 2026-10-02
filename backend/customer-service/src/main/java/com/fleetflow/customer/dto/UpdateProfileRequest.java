package com.fleetflow.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The only fields a customer may change on their own profile. Email and userId are
 * deliberately absent: the first is owned by auth-service and the second is the
 * identity link every other service references.
 */
@Schema(name = "UpdateProfileRequest", description = "Fields a customer may edit on their own profile")
public record UpdateProfileRequest(
        @NotBlank(message = "firstName is required")
        @Size(min = 2, max = 80, message = "firstName must be between 2 and 80 characters")
        @Schema(example = "Yasmine") String firstName,

        @NotBlank(message = "lastName is required")
        @Size(min = 2, max = 80, message = "lastName must be between 2 and 80 characters")
        @Schema(example = "Ben Salah") String lastName,

        @NotBlank(message = "phone is required")
        @Pattern(regexp = "^\\+?[0-9]{6,20}$", message = "phone must be 6 to 20 digits, optionally prefixed with +")
        @Schema(example = "+21620100101") String phone,

        @Size(max = 255, message = "address must not exceed 255 characters")
        @Schema(example = "12 Rue Habib Bourguiba") String address,

        @Size(max = 80, message = "city must not exceed 80 characters")
        @Schema(example = "Tunis") String city,

        @Size(max = 16, message = "postalCode must not exceed 16 characters")
        @Schema(example = "1000") String postalCode) {
}
