package com.fleetflow.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Transport wrapper carried by every Kafka message on the FleetFlow event bus.
 *
 * <p>{@code eventId} doubles as the idempotency key for consumers: a redelivered
 * message carries the same identifier, so consumers can safely ignore it.
 *
 * @param eventId       unique identifier of this event instance
 * @param eventType     logical event name, e.g. {@code ORDER_CREATED}
 * @param topic         logical topic the event was published to
 * @param correlationId identifier tying this event back to the originating request
 * @param timestamp     ISO-8601 instant the event was created
 * @param payload       event specific body
 */
public record EventEnvelope<T>(
        String eventId,
        String eventType,
        String topic,
        String correlationId,
        Instant timestamp,
        T payload) {

    public static <T> EventEnvelope<T> of(String eventType, String topic, T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID().toString(),
                eventType,
                topic,
                com.fleetflow.common.correlation.CorrelationId.getOrCreate(),
                Instant.now(),
                payload);
    }
}