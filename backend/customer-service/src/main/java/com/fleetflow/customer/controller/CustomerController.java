package com.fleetflow.customer.controller;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fleetflow.common.api.PageResponse;

import com.fleetflow.customer.dto.CustomerResponse;
import com.fleetflow.customer.dto.UpdateProfileRequest;
import com.fleetflow.customer.entity.Customer;
import com.fleetflow.customer.mapper.CustomerMapper;
import com.fleetflow.customer.service.CustomerService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/customers")
@Tag(name = "Customers", description = "Customer profiles, keyed by the auth-service user id")
public class CustomerController {

    private final CustomerService customerService;
    private final CustomerMapper mapper;

    public CustomerController(CustomerService customerService, CustomerMapper mapper) {
        this.customerService = customerService;
        this.mapper = mapper;
    }

    @GetMapping("/me")
    @Operation(summary = "Read my profile",
            description = "Returns the caller's profile, provisioning a skeleton from the JWT on first use")
    public CustomerResponse me() {
        return mapper.toResponse(customerService.getOrCreateCurrent());
    }

    @PutMapping("/me")
    @Operation(summary = "Update my profile",
            description = "Updates only the fields carried by the request; email and userId are not editable here")
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "Bean validation failed",
                    content = @Content(schema = @Schema(implementation = com.fleetflow.common.api.ApiErrorResponse.class))) })
    public CustomerResponse updateMe(@Valid @RequestBody UpdateProfileRequest request) {
        return mapper.toResponse(customerService.updateCurrentProfile(request));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS')")
    @Operation(summary = "List customers",
            description = "Staff listing, optionally filtered by email, name, phone, city or postal code")
    public PageResponse<CustomerResponse> search(
            @Parameter(in = ParameterIn.QUERY, description = "Free-text filter; blank returns every customer",
                    schema = @Schema(example = "tunis"))
            @RequestParam(required = false) String search,
            @Parameter(in = ParameterIn.QUERY, description = "Zero-based page index", schema = @Schema(example = "0"))
            @RequestParam(defaultValue = "0") int page,
            @Parameter(in = ParameterIn.QUERY, description = "Page size, 1 to 200", schema = @Schema(example = "20"))
            @RequestParam(defaultValue = "20") int size) {

        Page<Customer> result = customerService.search(search, page, size);
        List<CustomerResponse> content = result.getContent().stream().map(mapper::toResponse).toList();
        return PageResponse.from(result, content);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Read a customer by id",
            description = "Allowed for the owner of the row and for staff")
    @ApiResponses({
            @ApiResponse(responseCode = "403", description = "Not the owner and not staff",
                    content = @Content(schema = @Schema(implementation = com.fleetflow.common.api.ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such customer",
                    content = @Content(schema = @Schema(implementation = com.fleetflow.common.api.ApiErrorResponse.class))) })
    public ResponseEntity<CustomerResponse> byId(
            @Parameter(description = "Customer row id, not the auth user id", schema = @Schema(example = "8"))
            @PathVariable Long id) {
        return ResponseEntity.ok(mapper.toResponse(customerService.getById(id)));
    }
}
