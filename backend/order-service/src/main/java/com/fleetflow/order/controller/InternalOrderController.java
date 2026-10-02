package com.fleetflow.order.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fleetflow.common.api.ApiErrorResponse;

import com.fleetflow.order.dto.OrderItemLineResponse;
import com.fleetflow.order.dto.OrderSummaryResponse;
import com.fleetflow.order.service.OrderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Service-to-service reads. No user JWT is involved: the shared
 * {@code InternalServiceAuthFilter} is the gate, which is why these paths are
 * {@code permitAll} in Spring Security.
 */
@RestController
@RequestMapping("/internal/api/orders")
@Tag(name = "Internal Orders", description = "Service-to-service order lookups")
public class InternalOrderController {

    private final OrderService orderService;

    public InternalOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @Operation(summary = "Compact order summary", description = "Used by the notification service to describe an order.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The order",
                    content = @Content(schema = @Schema(implementation = OrderSummaryResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such order",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{id}/summary")
    public OrderSummaryResponse summary(
            @Parameter(description = "Order id", example = "1024") @PathVariable Long id) {
        return orderService.getSummary(id);
    }

    @Operation(summary = "Reservation lines",
            description = "Product ids and quantities only: what the warehouse service needs to reserve stock.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The ordered lines",
                    content = @Content(array = @ArraySchema(
                            schema = @Schema(implementation = OrderItemLineResponse.class)))),
            @ApiResponse(responseCode = "404", description = "No such order",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{id}/items")
    public List<OrderItemLineResponse> items(
            @Parameter(description = "Order id", example = "1024") @PathVariable Long id) {
        return orderService.getItemLines(id);
    }
}
