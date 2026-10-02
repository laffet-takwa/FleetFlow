package com.fleetflow.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fleetflow.auth.dto.AuthResponse;
import com.fleetflow.auth.dto.LoginRequest;
import com.fleetflow.auth.dto.RegisterRequest;
import com.fleetflow.auth.dto.UserResponse;
import com.fleetflow.auth.service.AuthService;
import com.fleetflow.common.api.ApiErrorResponse;
import com.fleetflow.common.security.SecurityUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "Authentication", description = "Registration, login and the caller's own profile")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "Register a customer account",
            description = "Creates a CUSTOMER account and returns a ready to use access token. "
                    + "The client cannot choose a role, and the supplied address is forwarded to "
                    + "customer-service so the profile starts populated.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created, token issued"),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Email already registered",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @Operation(summary = "Exchange credentials for an access token",
            description = "A wrong email, a wrong password and a disabled account are all reported "
                    + "identically so the endpoint cannot be used to discover which accounts exist.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authenticated"),
            @ApiResponse(responseCode = "401", description = "Invalid email or password",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @Operation(summary = "Profile of the authenticated caller",
            description = "Resolved from the JWT subject, which is the user id shared with every "
                    + "other FleetFlow service.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The authenticated user"),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/me")
    public UserResponse me() {
        return authService.loadCurrentUser(SecurityUtils.requireUserId());
    }
}