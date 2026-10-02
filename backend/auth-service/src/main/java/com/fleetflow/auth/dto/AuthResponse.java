package com.fleetflow.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Access token plus the identity it belongs to, so a client needs no second call. */
@Schema(name = "AuthResponse", description = "Issued access token and the authenticated user")
public record AuthResponse(

        @Schema(description = "Compact HS256 JWT to send as `Authorization: Bearer <token>`") String accessToken,
        @Schema(example = "Bearer", description = "Always the Bearer scheme") String tokenType,
        @Schema(example = "3600", description = "Access token lifetime in seconds") long expiresIn,
        @Schema(description = "The authenticated user") UserResponse user) {

    public static final String BEARER = "Bearer";
}