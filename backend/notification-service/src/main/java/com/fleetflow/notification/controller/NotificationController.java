package com.fleetflow.notification.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fleetflow.common.api.ApiErrorResponse;
import com.fleetflow.common.api.PageResponse;
import com.fleetflow.common.security.SecurityUtils;

import com.fleetflow.notification.dto.NotificationResponse;
import com.fleetflow.notification.dto.UnreadCountResponse;
import com.fleetflow.notification.service.NotificationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The notification centre of the signed in user. Every endpoint resolves its user from
 * the JWT subject, so there is no user id on the wire to tamper with: a notification
 * belonging to somebody else is a 403, not an empty list.
 */
@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications", description = "In-app notification centre and live browser stream")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    @Operation(summary = "List my notifications",
            description = "Newest first, optionally restricted to the unread ones")
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "Unusable page or size",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))) })
    public PageResponse<NotificationResponse> list(
            @Parameter(in = ParameterIn.QUERY, description = "Return only unread notifications",
                    schema = @Schema(example = "false"))
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @Parameter(in = ParameterIn.QUERY, description = "Zero-based page index", schema = @Schema(example = "0"))
            @RequestParam(defaultValue = "0") int page,
            @Parameter(in = ParameterIn.QUERY, description = "Page size, 1 to 100", schema = @Schema(example = "20"))
            @RequestParam(defaultValue = "20") int size) {

        return notificationService.list(SecurityUtils.requireUserId(), unreadOnly, page, size);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Count my unread notifications", description = "Drives the badge in the header")
    public UnreadCountResponse unreadCount() {
        return notificationService.unreadCount(SecurityUtils.requireUserId());
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Mark one notification as read",
            description = "Idempotent: reading an already read notification changes nothing")
    @ApiResponses({
            @ApiResponse(responseCode = "403", description = "The notification belongs to another user",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such notification",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))) })
    public ResponseEntity<Void> markAsRead(
            @Parameter(description = "Notification row id", schema = @Schema(example = "412"))
            @PathVariable Long id) {

        notificationService.markAsRead(SecurityUtils.requireUserId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark every unread notification as read",
            description = "Open browser streams receive a read-all event so their badge clears")
    public ResponseEntity<Void> markAllAsRead() {
        notificationService.markAllAsRead(SecurityUtils.requireUserId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete one notification",
            description = "Removes the row from the caller's notification centre")
    @ApiResponses({
            @ApiResponse(responseCode = "403", description = "The notification belongs to another user",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such notification",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))) })
    public ResponseEntity<Void> delete(
            @Parameter(description = "Notification row id", schema = @Schema(example = "412"))
            @PathVariable Long id) {

        notificationService.delete(SecurityUtils.requireUserId(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Subscribe to live notifications",
            description = "Server-sent events for the caller's own notifications: connected, notification, "
                    + "unread-count and read-all. The stream is recycled every 30 minutes, so the client "
                    + "reconnects on error")
    public SseEmitter stream() {
        return notificationService.subscribe(SecurityUtils.requireUserId());
    }
}
