package com.fleetflow.order.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "CreateOrderRequest", description = "Checkout payload")
public record CreateOrderRequest(

        @Schema(description = "Ordered lines, at least one and with no duplicated productId")
        @NotEmpty(message = "at least one order line is required")
        List<@Valid OrderLineRequest> items,

        @Schema(example = "12 Rue Habib Bourguiba")
        @NotBlank(message = "delivery address is required")
        @Size(max = 255, message = "delivery address must not exceed 255 characters")
        String deliveryAddress,

        @Schema(example = "Tunis")
        @NotBlank(message = "city is required")
        @Size(max = 80, message = "city must not exceed 80 characters")
        String city,

        @Schema(example = "1000")
        @NotBlank(message = "postal code is required")
        @Size(max = 16, message = "postal code must not exceed 16 characters")
        String postalCode) {

    @Schema(name = "OrderLineRequest", description = "A single requested product line")
    public record OrderLineRequest(

            @Schema(example = "7")
            @NotNull(message = "productId is required")
            Long productId,

            @Schema(example = "2", minimum = "1")
            @Min(value = 1, message = "quantity must be greater than zero")
            Integer quantity) {
    }
}
