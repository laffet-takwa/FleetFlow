package com.fleetflow.tracking.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import com.fleetflow.common.api.ApiErrorResponse;
import com.fleetflow.common.security.SecurityUtils;
import com.fleetflow.tracking.dto.LocationResponse;
import com.fleetflow.tracking.dto.LocationUpdateRequest;
import com.fleetflow.tracking.dto.TrackedDeliveryResponse;
import com.fleetflow.tracking.dto.TrackingHistoryResponse;
import com.fleetflow.tracking.service.LocationIngestionService;
import com.fleetflow.tracking.service.TrackingQueryService;
import com.fleetflow.tracking.sse.SseDeliveryRegistry;

/**
 * Live tracking API. Every method only binds, delegates and maps to HTTP; the rules live
 * in the ingestion and query services.
 */
@RestController
@RequestMapping("/api/tracking")
@Tag(name = "Tracking", description = "Driver positions: ingestion, history and live streams")
public class TrackingController {

    private static final String ERROR_RESPONSES = "Uniform error payload";

    private final LocationIngestionService ingestionService;
    private final TrackingQueryService queryService;
    private final SseDeliveryRegistry sseDeliveryRegistry;

    public TrackingController(LocationIngestionService ingestionService, TrackingQueryService queryService,
            SseDeliveryRegistry sseDeliveryRegistry) {
        this.ingestionService = ingestionService;
        this.queryService = queryService;
        this.sseDeliveryRegistry = sseDeliveryRegistry;
    }

    @PostMapping(value = "/locations", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Report a driver position",
            description = "Stores the point, refreshes the Redis read model and fans it out to open streams. "
                    + "Restricted to staff and to the driver assigned to the delivery. The demo "
                    + "simulation posts here every few seconds.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Position accepted and stored"),
            @ApiResponse(responseCode = "404", description = "Delivery is not tracked",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Delivery is finished or the point is out of order",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Not the assigned driver",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public LocationResponse reportLocation(@Valid @RequestBody LocationUpdateRequest request) {
        return ingestionService.ingest(request, SecurityUtils.requireUserId(), SecurityUtils.isStaff());
    }

    @GetMapping(value = "/{deliveryId}/latest", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Newest known position",
            description = "Served from the Redis read model, falling back to the MongoDB trail when the "
                    + "cache entry has expired.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Newest position"),
            @ApiResponse(responseCode = "404", description = "No tracked delivery or no position yet",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public LocationResponse latest(
            @Parameter(description = "Delivery id", example = "3") @PathVariable long deliveryId) {
        return queryService.latest(deliveryId);
    }

    @GetMapping(value = "/{deliveryId}/history", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Recorded trail",
            description = "Positions ordered oldest first so a map polyline can be drawn in one pass.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Trail, possibly truncated to the limit"),
            @ApiResponse(responseCode = "404", description = "No tracked delivery",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public TrackingHistoryResponse history(
            @Parameter(description = "Delivery id", example = "3") @PathVariable long deliveryId,
            @Parameter(in = ParameterIn.QUERY, description = "Maximum number of points, capped at 1000",
                    example = "200")
            @RequestParam(defaultValue = "200") int limit) {
        return queryService.history(deliveryId, limit);
    }

    @GetMapping(value = "/{deliveryId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Subscribe to live positions",
            description = "Opens a Server-Sent Events stream. A `connected` event carrying the current position "
                    + "arrives immediately, then one `location` event per update. The stream is closed by the "
                    + "server as soon as the delivery reaches a terminal state.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Event stream opened"),
            @ApiResponse(responseCode = "409", description = "Delivery is not active",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public SseEmitter stream(
            @Parameter(description = "Delivery id", example = "3") @PathVariable long deliveryId) {
        queryService.requireStreamable(deliveryId);
        return sseDeliveryRegistry.subscribe(deliveryId);
    }

    @GetMapping(value = "/active", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "All deliveries currently being tracked",
            description = "Newest positions are resolved from Redis in a single round trip. `online` is true while "
                    + "the last fix is younger than the configured staleness window.")
    @ApiResponse(responseCode = "200", description = "Tracked deliveries, newest position included")
    public List<TrackedDeliveryResponse> active() {
        return queryService.active();
    }

    @GetMapping(value = "/{deliveryId}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Tracking state of one delivery",
            description = "Delivery summary with the newest known position and the online flag.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tracking state"),
            @ApiResponse(responseCode = "404", description = ERROR_RESPONSES,
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public TrackedDeliveryResponse status(
            @Parameter(description = "Delivery id", example = "3") @PathVariable long deliveryId) {
        return queryService.status(deliveryId);
    }
}
