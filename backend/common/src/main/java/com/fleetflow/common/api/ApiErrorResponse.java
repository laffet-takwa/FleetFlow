package com.fleetflow.common.api;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The single error shape returned by every FleetFlow service, rendered from
 * {@link com.fleetflow.common.exception.ErrorCode}.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Schema(name = "ApiErrorResponse", description = "Uniform error payload")
public record ApiErrorResponse(
        @Schema(example = "2026-10-02T13:53:11.204Z") Instant timestamp,
        @Schema(example = "400") int status,
        @Schema(example = "BAD_REQUEST") String error,
        @Schema(example = "Product quantity must be greater than zero") String message,
        @Schema(example = "/api/orders") String path,
        @Schema(description = "Correlation id, also returned in the X-Correlation-ID header")
        String correlationId,
        @Schema(description = "Per-field validation failures, when applicable")
        List<FieldViolation> violations) {

    public record FieldViolation(
            @Schema(example = "items[0].quantity") String field,
            @Schema(example = "must be greater than 0") String message) {
    }
}