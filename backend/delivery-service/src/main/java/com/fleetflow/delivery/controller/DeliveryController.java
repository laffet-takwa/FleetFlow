package com.fleetflow.delivery.controller;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fleetflow.common.api.PageResponse;
import com.fleetflow.common.security.SecurityUtils;
import com.fleetflow.delivery.dto.AssignDeliveryRequest;
import com.fleetflow.delivery.dto.CreateDeliveryRequest;
import com.fleetflow.delivery.dto.DeliveryCustomerContact;
import com.fleetflow.delivery.dto.DeliveryKpiResponse;
import com.fleetflow.delivery.dto.DeliveryResponse;
import com.fleetflow.delivery.dto.UpdateDeliveryStatusRequest;
import com.fleetflow.delivery.service.DeliveryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/deliveries")
@Tag(name = "Deliveries", description = "Delivery lifecycle: creation, allocation and status transitions")
public class DeliveryController {

    private static final String STAFF_OR_DRIVER = "hasAnyRole('ADMIN','OPERATIONS','DRIVER')";
    private static final String STAFF = "hasAnyRole('ADMIN','OPERATIONS')";

    private final DeliveryService deliveryService;

    public DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @GetMapping
    @PreAuthorize(STAFF)
    @Operation(summary = "Search deliveries",
            description = "Filter by status, driver, vehicle or creation window. Staff only.")
    public PageResponse<DeliveryResponse> search(
            @Parameter(description = "Delivery status") @RequestParam(required = false) String status,
            @Parameter(description = "Assigned driver id") @RequestParam(required = false) Long driverId,
            @Parameter(description = "Assigned vehicle id") @RequestParam(required = false) Long vehicleId,
            @Parameter(description = "Created at or after") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Created before") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "0-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {

        return deliveryService.search(status, driverId, vehicleId, from, to, page, size);
    }

    @GetMapping("/active")
    @PreAuthorize(STAFF)
    @Operation(summary = "List in-flight deliveries",
            description = "Every delivery that still occupies a driver and a vehicle, for the live operations map.")
    public List<DeliveryResponse> active() {
        return deliveryService.findActive();
    }

    @GetMapping("/kpi")
    @PreAuthorize(STAFF)
    @Operation(summary = "Delivery KPIs", description = "Headline counters for the operations dashboard.")
    public DeliveryKpiResponse kpi() {
        return deliveryService.kpi();
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "My deliveries", description = "Deliveries assigned to the calling driver, newest first.")
    public List<DeliveryResponse> mine() {
        return deliveryService.findForDriver(SecurityUtils.requireUserId());
    }

    @GetMapping("/{id}")
    @PreAuthorize(STAFF_OR_DRIVER + " or hasRole('CUSTOMER')")
    @Operation(summary = "Read a delivery",
            description = "Visible to staff, to the assigned driver and to the customer who placed the order.")
    @ApiResponse(responseCode = "404", description = "No such delivery")
    public DeliveryResponse get(@PathVariable Long id) {
        return deliveryService.getVisibleToCaller(id);
    }

    @GetMapping("/{id}/customer")
    @PreAuthorize(STAFF_OR_DRIVER)
    @Operation(summary = "Customer contact for the assigned driver",
            description = "Served from the snapshot stored on the delivery; customer-service is not called.")
    @ApiResponse(responseCode = "403", description = "The delivery is not assigned to the calling driver")
    public DeliveryCustomerContact customerContact(@PathVariable Long id) {
        return deliveryService.getCustomerContact(id);
    }

    @PostMapping
    @PreAuthorize(STAFF)
    @Operation(summary = "Create a delivery manually",
            description = "For an order that has no delivery yet, e.g. when the reservation event was lost.")
    public ResponseEntity<DeliveryResponse> create(@Valid @RequestBody CreateDeliveryRequest request) {
        DeliveryResponse created = deliveryService.create(request);
        return ResponseEntity.created(URI.create("/api/deliveries/" + created.id())).body(created);
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize(STAFF)
    @Operation(summary = "Assign a driver and a vehicle",
            description = "Both resources must be AVAILABLE. A failed delivery is requeued, not reassigned.")
    @ApiResponse(responseCode = "409", description = "Driver or vehicle is not available, or the delivery is not CREATED")
    public DeliveryResponse assign(@PathVariable Long id, @Valid @RequestBody AssignDeliveryRequest request) {
        return deliveryService.assign(request.driverId(), request.vehicleId(), id);
    }

    @PostMapping("/{id}/status")
    @PreAuthorize(STAFF_OR_DRIVER)
    @Operation(summary = "Move a delivery to another status",
            description = "A driver may only move their own delivery; staff may move any. "
                    + "Completing, failing or cancelling releases the driver and the vehicle.")
    @ApiResponse(responseCode = "409", description = "The status machine does not allow this transition")
    @ApiResponse(responseCode = "403", description = "The delivery is not assigned to the calling driver")
    public DeliveryResponse changeStatus(@PathVariable Long id,
            @Valid @RequestBody UpdateDeliveryStatusRequest request) {

        return deliveryService.changeStatus(id, request.status(), request.reason(),
                SecurityUtils.requireUserId(), SecurityUtils.currentRole());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(STAFF)
    @Operation(summary = "Cancel a delivery", description = "Terminal; the driver and vehicle go back to AVAILABLE.")
    public DeliveryResponse cancel(@PathVariable Long id, @RequestBody UpdateDeliveryStatusRequest request) {
        return deliveryService.changeStatus(id, "CANCELLED", request == null ? null : request.reason(),
                SecurityUtils.requireUserId(), SecurityUtils.currentRole());
    }

    @PostMapping("/{id}/requeue")
    @PreAuthorize(STAFF)
    @Operation(summary = "Requeue a failed delivery",
            description = "FAILED to ASSIGNED with the same crew. Staff only.")
    @ApiResponse(responseCode = "409", description = "The delivery is not FAILED, or the crew is unavailable")
    public DeliveryResponse requeue(@PathVariable Long id,
            @RequestBody(required = false) UpdateDeliveryStatusRequest request) {
        return deliveryService.requeue(id, request == null ? null : request.reason());
    }
}
