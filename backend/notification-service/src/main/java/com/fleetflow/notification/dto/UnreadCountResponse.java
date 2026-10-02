package com.fleetflow.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "UnreadCountResponse", description = "Number of unread notifications the caller has")
public record UnreadCountResponse(
        @Schema(example = "3") long unreadCount) {
}
