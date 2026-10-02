package com.fleetflow.order.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fleetflow.common.api.ApiErrorResponse;
import com.fleetflow.common.api.PageResponse;

import com.fleetflow.order.dto.CancelOrderRequest;
import com.fleetflow.order.dto.CreateOrderRequest;
import com.fleetflow.order.dto.DailyOrderCount;
import com.fleetflow.order.dto.OrderKpiResponse;
import com.fleetflow.order.dto.OrderResponse;
import com.fleetflow.order.dto.UpdateOrderStatusRequest;
import com.fleetflow.order.entity.OrderStatus;
import com.fleetflow.order.service.OrderAnalyticsService;
import com.fleetflow.order.service.OrderKpiService;
import com.fleetflow.order.service.OrderService;

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

@Tag(name = "Orders", description = "Order capture, pricing and lifecycle")
@RestController
@Validated
@RequestMapping("/api/orders")
public class OrderController {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_ANALYTICS_DAYS = 14;

    private final OrderService orderService;
    private final OrderKpiService kpiService;
    private final OrderAnalyticsService analyticsService;

    public OrderController(OrderService orderService, OrderKpiService kpiService,
            OrderAnalyticsService analyticsService) {

        this.orderService = orderService;
        this.kpiService = kpiService;
        this.analyticsService = analyticsService;
    }

    @Operation(summary = "Place an order",
            description = "Prices the basket from the live catalogue, so the client never sends an amount. "
                    + "The order starts in CREATED and triggers the warehouse reservation.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Order accepted",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))),
            @ApiResponse(responseCode = "400", description = "Empty or duplicated lines",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Unknown product",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "A product is no longer active",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "warehouse-service is unreachable",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    public OrderResponse create(@Valid @RequestBody CreateOrderRequest request) {
        return orderService.create(request);
    }

    @Operation(summary = "List orders",
            description = "A customer sees only their own orders; staff see every order. "
                    + "`search` matches the order id when numeric, otherwise the delivery address or city. "
                    + "Dates are inclusive and sorting is restricted to createdAt, totalAmount and status.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Matching page of orders"),
            @ApiResponse(responseCode = "400", description = "Invalid paging or sort parameter",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping
    public PageResponse<OrderResponse> search(
            @Parameter(description = "Restrict to a single lifecycle status", example = "PROCESSING")
            @RequestParam(required = false) OrderStatus status,
            @Parameter(description = "Order id, delivery address or city", example = "Tunis")
            @RequestParam(required = false) String search,
            @Parameter(description = "Earliest created date, inclusive", example = "2026-09-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Latest created date, inclusive", example = "2026-09-30")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Zero based page index", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size, capped at " + MAX_PAGE_SIZE, example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @Parameter(description = "createdAt, totalAmount or status, optionally suffixed with ,asc or ,desc",
                    example = "createdAt,desc")
            @RequestParam(required = false) String sort) {

        return orderService.search(status, search, from, to, page, Math.min(size, MAX_PAGE_SIZE), sort);
    }

    @Operation(summary = "Order counts by lifecycle bucket", description = "Dashboard tiles for operations.")
    @ApiResponses(@ApiResponse(responseCode = "403", description = "Caller is neither ADMIN nor OPERATIONS",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))))
    @GetMapping("/kpi")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS')")
    public OrderKpiResponse kpi() {
        return kpiService.kpis();
    }

    @Operation(summary = "Daily order volume",
            description = "One entry per day for the last `days` days, zero filled so the chart has no holes.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Dense daily series, oldest first",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = DailyOrderCount.class)))),
            @ApiResponse(responseCode = "403", description = "Caller is neither ADMIN nor OPERATIONS",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/analytics/daily")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS')")
    public List<DailyOrderCount> daily(
            @Parameter(description = "Window length, 1 to 90 days", example = "14")
            @RequestParam(defaultValue = "" + DEFAULT_ANALYTICS_DAYS) @Min(1) @Max(90) int days) {

        return analyticsService.dailyCounts(days);
    }

    @Operation(summary = "Order detail", description = "Items and the full status timeline, for the order screen.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The order",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))),
            @ApiResponse(responseCode = "403", description = "Not the owning customer and not staff",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such order",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{id}")
    public OrderResponse getById(@Parameter(description = "Order id", example = "1024") @PathVariable Long id) {
        return orderService.getById(id);
    }

    @Operation(summary = "Cancel an order",
            description = "A customer may cancel while the order is CREATED or CONFIRMED; staff may also cancel "
                    + "while it is PROCESSING. Anything later is a 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order cancelled",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))),
            @ApiResponse(responseCode = "403", description = "Not the owning customer and not staff",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Too late to cancel",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(
            @Parameter(description = "Order id", example = "1024") @PathVariable Long id,
            @Parameter(description = "Optional; a default reason is recorded when omitted")
            @RequestBody(required = false) @Valid CancelOrderRequest request) {

        return orderService.cancel(id, request);
    }

    @Operation(summary = "Move an order to another status",
            description = "Manual hook for the two states no event covers, PROCESSING and READY_FOR_DELIVERY. "
                    + "An unreachable target is a 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status changed",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))),
            @ApiResponse(responseCode = "403", description = "Caller is neither ADMIN nor OPERATIONS",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Illegal transition",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS')")
    public OrderResponse updateStatus(
            @Parameter(description = "Order id", example = "1024") @PathVariable Long id,
            @Valid @RequestBody UpdateOrderStatusRequest request) {

        return orderService.updateStatus(id, request);
    }
}
