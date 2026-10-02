package com.fleetflow.delivery.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fleetflow.common.api.PageResponse;
import com.fleetflow.common.security.SecurityUtils;
import com.fleetflow.delivery.dto.DriverResponse;
import com.fleetflow.delivery.dto.DriverStatusRequest;
import com.fleetflow.delivery.service.DriverService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/drivers")
@Tag(name = "Drivers", description = "Driver roster, availability and current assignment")
public class DriverController {

    private static final String STAFF = "hasAnyRole('ADMIN','OPERATIONS')";

    private final DriverService driverService;

    public DriverController(DriverService driverService) {
        this.driverService = driverService;
    }

    @GetMapping
    @PreAuthorize(STAFF)
    @Operation(summary = "Search drivers",
            description = "Filter by status, and match the search term against name, licence or phone. Staff only.")
    public PageResponse<DriverResponse> search(
            @Parameter(description = "Driver status") @RequestParam(required = false) String status,
            @Parameter(description = "Name, licence number or phone") @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return driverService.search(status, search, page, size);
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "My driver record", description = "Resolved from the JWT subject of the caller.")
    @ApiResponse(responseCode = "404", description = "The caller is not registered as a driver")
    public DriverResponse me() {
        return driverService.getByUserId(SecurityUtils.requireUserId());
    }

    @PutMapping("/me/status")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "Declare my availability",
            description = "A driver may set AVAILABLE or OFFLINE. ON_DELIVERY is reserved for operations.")
    @ApiResponse(responseCode = "409", description = "ON_DELIVERY cannot be set by the driver, "
            + "or a delivery is still in progress")
    public DriverResponse setOwnStatus(@Valid @RequestBody DriverStatusRequest request) {
        return driverService.setOwnStatus(SecurityUtils.requireUserId(), request.status());
    }

    @GetMapping("/{id}")
    @PreAuthorize(STAFF + " or hasRole('DRIVER')")
    @Operation(summary = "Read a driver", description = "Staff may read anybody, a driver only themselves.")
    public DriverResponse get(@PathVariable Long id) {
        return driverService.getForCaller(id);
    }
}
