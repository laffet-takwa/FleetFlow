package com.fleetflow.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Self-service registration. There is deliberately no {@code role} component: a
 * public caller may only ever create a customer, and a role supplied in the body
 * would otherwise be bound straight onto the entity.
 */
@Schema(name = "RegisterRequest", description = "New customer account")
public record RegisterRequest(

        @Schema(example = "Sonia")
        @NotBlank(message = "First name is required")
        @Size(min = 2, max = 80, message = "First name must be between 2 and 80 characters")
        String firstName,

        @Schema(example = "Trabelsi")
        @NotBlank(message = "Last name is required")
        @Size(min = 2, max = 80, message = "Last name must be between 2 and 80 characters")
        String lastName,

        @Schema(example = "sonia.trabelsi@example.com")
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address")
        @Size(max = 180, message = "Email must not exceed 180 characters")
        String email,

        @Schema(example = "+21620123456")
        @NotBlank(message = "Phone is required")
        @Pattern(regexp = "^\\+?[0-9]{6,20}$", message = "Phone must be 6 to 20 digits with an optional leading +")
        String phone,

        @Schema(example = "Password123!")
        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).*$",
                message = "Password must contain at least one letter and one digit")
        String password,

        @Schema(example = "12 rue des Palmiers, Tunis")
        @Size(max = 255, message = "Address must not exceed 255 characters")
        String address) {
}