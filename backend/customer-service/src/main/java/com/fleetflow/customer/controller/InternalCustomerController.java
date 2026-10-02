package com.fleetflow.customer.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fleetflow.customer.dto.CustomerContactResponse;
import com.fleetflow.customer.mapper.CustomerMapper;
import com.fleetflow.customer.service.CustomerService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Service-to-service reads. No user JWT is involved: the shared
 * {@code InternalServiceAuthFilter} is the gate, which is why these paths are
 * {@code permitAll} in Spring Security.
 */
@RestController
@RequestMapping("/internal/api/customers")
@Tag(name = "Internal Customers", description = "Service-to-service customer lookups")
public class InternalCustomerController {

    private final CustomerService customerService;
    private final CustomerMapper mapper;

    public InternalCustomerController(CustomerService customerService, CustomerMapper mapper) {
        this.customerService = customerService;
        this.mapper = mapper;
    }

    @GetMapping("/by-user/{userId}/contact")
    @Operation(summary = "Contact details of a customer, by auth user id",
            description = "Used by the delivery service before a delivery starts")
    @ApiResponse(responseCode = "404", description = "No customer profile for that user id",
            content = @Content(schema = @Schema(implementation = com.fleetflow.common.api.ApiErrorResponse.class)))
    public CustomerContactResponse contact(
            @Parameter(description = "auth-service user id, not the customer row id", schema = @Schema(example = "8"))
            @PathVariable Long userId) {
        return mapper.toContactResponse(customerService.getContactByUserId(userId));
    }
}
