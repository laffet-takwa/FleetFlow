package com.fleetflow.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "LoginRequest", description = "Email and password credentials")
public record LoginRequest(

        @Schema(example = "customer1@fleetflow.local")
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address")
        @Size(max = 180, message = "Email must not exceed 180 characters")
        String email,

        @Schema(example = "Password123!", format = "password")
        @NotBlank(message = "Password is required")
        String password) {
}