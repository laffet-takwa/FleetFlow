package com.fleetflow.warehouse.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fleetflow.common.api.ApiErrorResponse;

import com.fleetflow.warehouse.dto.CreateWarehouseRequest;
import com.fleetflow.warehouse.dto.UpdateWarehouseRequest;
import com.fleetflow.warehouse.dto.WarehouseResponse;
import com.fleetflow.warehouse.service.WarehouseService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "Warehouses", description = "Fulfilment sites")
@RestController
@Validated
@RequestMapping("/api/warehouses")
public class WarehouseController {

    private final WarehouseService warehouseService;

    public WarehouseController(WarehouseService warehouseService) {
        this.warehouseService = warehouseService;
    }

    @Operation(summary = "List warehouses",
            description = "Any authenticated caller may read; each row carries how many distinct products it holds.")
    @ApiResponse(responseCode = "200", description = "Every warehouse",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = WarehouseResponse.class))))
    @GetMapping
    public List<WarehouseResponse> list() {
        return warehouseService.list();
    }

    @Operation(summary = "Open a warehouse", description = "Staff only. New sites start ACTIVE.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Warehouse created",
                    content = @Content(schema = @Schema(implementation = WarehouseResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid body",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Caller is neither ADMIN nor OPERATIONS",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "A warehouse with that name already exists",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS')")
    public WarehouseResponse create(@Valid @RequestBody CreateWarehouseRequest request) {
        return warehouseService.create(request);
    }

    @Operation(summary = "Edit a warehouse",
            description = "Partial edit: an omitted field is left alone. Set INACTIVE to close the site "
                    + "to new reservations.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Warehouse updated",
                    content = @Content(schema = @Schema(implementation = WarehouseResponse.class))),
            @ApiResponse(responseCode = "403", description = "Caller is neither ADMIN nor OPERATIONS",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such warehouse",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS')")
    public WarehouseResponse update(
            @Parameter(description = "Warehouse id", example = "1") @PathVariable Long id,
            @Valid @RequestBody UpdateWarehouseRequest request) {

        return warehouseService.update(id, request);
    }
}