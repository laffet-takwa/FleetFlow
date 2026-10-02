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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fleetflow.common.api.ApiErrorResponse;
import com.fleetflow.common.api.PageResponse;

import com.fleetflow.warehouse.dto.CreateProductRequest;
import com.fleetflow.warehouse.dto.ProductResponse;
import com.fleetflow.warehouse.dto.UpdateProductRequest;
import com.fleetflow.warehouse.service.ProductService;

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

@Tag(name = "Products", description = "Product catalogue")
@RestController
@Validated
@RequestMapping("/api/products")
public class ProductController {

    private static final int MAX_PAGE_SIZE = 200;

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @Operation(summary = "Browse the catalogue",
            description = "Any authenticated caller may read: customers browse at checkout. "
                    + "`search` matches the SKU or the name, case-insensitively. "
                    + "The stock reading comes from the preferred warehouse.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Matching page of products"),
            @ApiResponse(responseCode = "400", description = "Invalid paging parameter",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping
    public PageResponse<ProductResponse> search(
            @Parameter(description = "SKU or product name", example = "FF-EL-0001")
            @RequestParam(required = false) String search,
            @Parameter(description = "Restrict to one category", example = "ELECTRONICS")
            @RequestParam(required = false) String category,
            @Parameter(description = "Restrict to active or retired products", example = "true")
            @RequestParam(required = false) Boolean active,
            @Parameter(description = "Zero based page index", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size, capped at " + MAX_PAGE_SIZE, example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        return productService.search(search, category, active, page, Math.min(size, MAX_PAGE_SIZE));
    }

    /**
     * Declared before {@code /{id}} so the mapping is unambiguous to a reader as well
     * as to the dispatcher.
     */
    @Operation(summary = "Categories in use",
            description = "Feeds the category filter; only categories that exist in the catalogue are returned.")
    @ApiResponse(responseCode = "200", description = "Category names",
            content = @Content(array = @ArraySchema(schema = @Schema(type = "string"))))
    @GetMapping("/categories")
    public List<String> categories() {
        return productService.listCategories();
    }

    @Operation(summary = "Product detail", description = "Includes the derived stock status.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The product",
                    content = @Content(schema = @Schema(implementation = ProductResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such product",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{id}")
    public ProductResponse getById(@Parameter(description = "Product id", example = "1") @PathVariable Long id) {
        return productService.getById(id);
    }

    @Operation(summary = "Create a product", description = "Staff only.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Product created",
                    content = @Content(schema = @Schema(implementation = ProductResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid body or unknown category",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Caller is neither ADMIN nor OPERATIONS",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The SKU is already taken",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS')")
    public ProductResponse create(@Valid @RequestBody CreateProductRequest request) {
        return productService.create(request);
    }

    @Operation(summary = "Edit a product",
            description = "Partial edit: an omitted field is left alone. The SKU is immutable, "
                    + "because order lines and inventory rows quote it.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Product updated",
                    content = @Content(schema = @Schema(implementation = ProductResponse.class))),
            @ApiResponse(responseCode = "403", description = "Caller is neither ADMIN nor OPERATIONS",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such product",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS')")
    public ProductResponse update(
            @Parameter(description = "Product id", example = "1") @PathVariable Long id,
            @Valid @RequestBody UpdateProductRequest request) {

        return productService.update(id, request);
    }
}