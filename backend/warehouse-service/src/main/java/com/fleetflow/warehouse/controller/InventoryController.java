package com.fleetflow.warehouse.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fleetflow.common.api.ApiErrorResponse;
import com.fleetflow.common.api.PageResponse;

import com.fleetflow.warehouse.dto.AdjustInventoryRequest;
import com.fleetflow.warehouse.dto.InventoryResponse;
import com.fleetflow.warehouse.dto.LowStockResponse;
import com.fleetflow.warehouse.dto.StockSummary;
import com.fleetflow.warehouse.entity.StockStatus;
import com.fleetflow.warehouse.service.InventoryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Tag(name = "Inventory", description = "Stock levels and manual corrections")
@RestController
@Validated
@RequestMapping("/api/inventory")
public class InventoryController {

    private static final int MAX_PAGE_SIZE = 200;

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @Operation(summary = "Stock levels",
            description = "Any authenticated caller may read. `stockStatus` is filtered in the query, not "
                    + "after paging, so the reported totals match the filter.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Matching page of stock levels"),
            @ApiResponse(responseCode = "400", description = "Invalid paging parameter",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping
    public PageResponse<InventoryResponse> search(
            @Parameter(description = "Restrict to one site", example = "1")
            @RequestParam(required = false) Long warehouseId,
            @Parameter(description = "Product SKU or name", example = "FF-GR-0001")
            @RequestParam(required = false) String search,
            @Parameter(description = "Derived stock status filter", example = "LOW_STOCK")
            @RequestParam(required = false) StockStatus stockStatus,
            @Parameter(description = "Restrict to one category", example = "GROCERY")
            @RequestParam(required = false) String category,
            @Parameter(description = "Zero based page index", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size, capped at " + MAX_PAGE_SIZE, example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        return inventoryService.search(warehouseId, search, stockStatus, category, page,
                Math.min(size, MAX_PAGE_SIZE));
    }

    @Operation(summary = "Levels needing attention",
            description = "LOW_STOCK and OUT_OF_STOCK rows, emptiest first.")
    @ApiResponse(responseCode = "200", description = "Stock levels at or below the threshold",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = LowStockResponse.class))))
    @GetMapping("/low-stock")
    public List<LowStockResponse> lowStock() {
        return inventoryService.lowStock();
    }

    @Operation(summary = "Stock summary",
            description = "Counts by derived stock status across every warehouse.")
    @ApiResponse(responseCode = "200", description = "The summary strip",
            content = @Content(schema = @Schema(implementation = StockSummary.class)))
    @GetMapping("/summary")
    public StockSummary summary() {
        return inventoryService.summary();
    }

    @Operation(summary = "Correct a stock level",
            description = "Applies a signed correction to the available quantity. Staff only. "
                    + "A correction that would take the level below zero is rejected as a 409 rather than "
                    + "applied, because available stock is what the next reservation depends on.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Level corrected",
                    content = @Content(schema = @Schema(implementation = InventoryResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid body",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Caller is neither ADMIN nor OPERATIONS",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such stock level",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The correction would drive availability below zero",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS')")
    public InventoryResponse adjust(
            @Parameter(description = "Inventory row id", example = "1") @PathVariable Long id,
            @Valid @RequestBody AdjustInventoryRequest request) {

        return inventoryService.adjust(id, request);
    }
}