package com.fleetflow.notification.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "NotificationResponse", description = "A single notification addressed to the caller")
public record NotificationResponse(
        @Schema(example = "412") Long id,
        @Schema(example = "ORDER_CONFIRMED") String type,
        @Schema(example = "Order #42 confirmed") String title,
        @Schema(example = "Your order #42 has been received and is being prepared.") String message,
        @Schema(example = "42") Long orderId,
        @Schema(example = "17") Long deliveryId,
        @Schema(example = "INFO", allowableValues = { "INFO", "SUCCESS", "WARNING", "ERROR" }) String level,
        @Schema(description = "False until the recipient reads the notification", example = "false") boolean read,
        @Schema(example = "2026-10-02T13:41:07.512Z") Instant createdAt) {
}
