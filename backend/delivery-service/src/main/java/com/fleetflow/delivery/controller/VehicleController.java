package com.fleetflow.delivery.controller;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fleetflow.common.api.PageResponse;
import com.fleetflow.delivery.dto.CreateVehicleRequest;
import com.fleetflow.delivery.dto.UpdateVehicleRequest;
import com.fleetflow.delivery.dto.VehicleResponse;
import com.fleetflow.delivery.service.VehicleService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/vehicles")
@Tag(name = "Vehicles", description = "Fleet registry and availability")
@PreAuthorize("hasAnyRole('ADMIN','OPERATIONS')")
public class VehicleController {

    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    @GetMapping
    @Operation(summary = "Search vehicles", description = "Filter by status and type, staff only.")
    public PageResponse<VehicleResponse> search(
            @Parameter(description = "Vehicle status") @RequestParam(required = false) String status,
            @Parameter(description = "VAN, MOTORCYCLE, TRUCK or CAR") @RequestParam(required = false) String type,
            @Parameter(description = "Registration number or type") @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return vehicleService.search(status, type, search, page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Read a vehicle")
    public VehicleResponse get(@PathVariable Long id) {
        return vehicleService.get(id);
    }

    @PostMapping
    @Operation(summary = "Register a vehicle")
    public ResponseEntity<VehicleResponse> create(@Valid @RequestBody CreateVehicleRequest request) {
        VehicleResponse created = vehicleService.create(request);
        return ResponseEntity.created(URI.create("/api/vehicles/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a vehicle", description = "Registration, type, capacity and status.")
    public VehicleResponse update(@PathVariable Long id, @Valid @RequestBody UpdateVehicleRequest request) {
        return vehicleService.update(id, request);
    }
}
