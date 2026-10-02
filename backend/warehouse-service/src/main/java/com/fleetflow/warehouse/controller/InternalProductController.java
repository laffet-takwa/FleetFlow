package com.fleetflow.warehouse.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fleetflow.common.api.ApiErrorResponse;

import com.fleetflow.warehouse.dto.ProductSnapshot;
import com.fleetflow.warehouse.service.ProductService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Service-to-service catalogue reads for the order service.
 *
 * <p>No user JWT is involved: the shared {@code InternalServiceAuthFilter} is the gate,
 * which is why these paths are {@code permitAll} in Spring Security. They are
 * deliberately absent from the gateway route table, so nothing outside the platform
 * network can reach them even with a token.
 */
@RestController
@RequestMapping("/internal/api/products")
@Tag(name = "Internal Products", description = "Service-to-service catalogue lookups")
public class InternalProductController {

    private final ProductService productService;

    public InternalProductController(ProductService productService) {
        this.productService = productService;
    }

    @Operation(summary = "Catalogue rows for a set of ids",
            description = "Comma separated `ids`. Ids the catalogue does not know are absent from the "
                    + "array, which is how the caller detects an unknown product.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The matching rows",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = ProductSnapshot.class)))),
            @ApiResponse(responseCode = "403", description = "Missing or wrong X-Internal-Token",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping
    public List<ProductSnapshot> findByIds(
            @Parameter(description = "Comma separated product ids", example = "1,2,3")
            @RequestParam(required = false) List<Long> ids) {

        return productService.snapshots(ids);
    }

    @Operation(summary = "One catalogue row", description = "Used when a single product is needed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The product",
                    content = @Content(schema = @Schema(implementation = ProductSnapshot.class))),
            @ApiResponse(responseCode = "403", description = "Missing or wrong X-Internal-Token",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such product",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{id}")
    public ProductSnapshot getById(@Parameter(description = "Product id", example = "1") @PathVariable Long id) {
        return productService.snapshot(id);
    }
}