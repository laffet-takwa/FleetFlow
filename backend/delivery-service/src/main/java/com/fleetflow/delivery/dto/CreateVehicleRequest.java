package com.fleetflow.delivery.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "CreateVehicleRequest")
public record CreateVehicleRequest(
        @NotBlank @Size(max = 20) String registrationNumber,
        @NotNull @Schema(example = "VAN", allowableValues = { "VAN", "MOTORCYCLE", "TRUCK", "CAR" }) String type,
        @NotNull @Min(1) @Max(100000) @Schema(example = "800") Integer capacity,
        @Schema(description = "Defaults to AVAILABLE when omitted", example = "AVAILABLE") String status) {
}
