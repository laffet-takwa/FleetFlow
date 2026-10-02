package com.fleetflow.warehouse.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.fleetflow.common.correlation.CorrelationId;
import com.fleetflow.common.correlation.CorrelationIdScope;
import com.fleetflow.common.event.EventEnvelope;
import com.fleetflow.common.event.payload.OrderCreatedPayload;
import com.fleetflow.common.idempotency.IdempotencyService;

import com.fleetflow.warehouse.service.InventoryReservationService;

/**
 * Turns {@code order.created} into stock reservations.
 *
 * <p>The shell is the platform standard: values arrive as JSON strings, the originating
 * correlation id is restored so every log line and every event republished while
 * handling it points back at the same request, and
 * {@link IdempotencyService} makes the body run at most once. The reservation rules
 * themselves live in {@link InventoryReservationService}, where they are unit tested.
 */
@Component
public class OrderCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCreatedListener.class);

    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotencyService;
    private final InventoryReservationService reservationService;

    public OrderCreatedListener(ObjectMapper objectMapper,
            IdempotencyService idempotencyService,
            InventoryReservationService reservationService) {

        this.objectMapper = objectMapper;
        this.idempotencyService = idempotencyService;
        this.reservationService = reservationService;
    }

    @KafkaListener(topics = "${fleetflow.kafka.topics.order-created}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onOrderCreated(String json) {
        EventEnvelope<OrderCreatedPayload> envelope =
                read(json, new TypeReference<EventEnvelope<OrderCreatedPayload>>() {
                });

        try (CorrelationIdScope ignored = CorrelationIdScope.open(envelope.correlationId())) {
            idempotencyService.executeOnce(envelope.eventId(), envelope.eventType(),
                    () -> reservationService.reserve(envelope.payload()));
        }
    }

    /**
     * A payload this service cannot read is a contract break, so it is rethrown for the
     * container error handler rather than being dropped on the floor.
     */
    private <T> EventEnvelope<T> read(String json, TypeReference<EventEnvelope<T>> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            log.error("Rejected an unreadable order event [correlationId={}]: {}", CorrelationId.getOrCreate(),
                    ex.getOriginalMessage());
            throw new IllegalStateException("Unreadable order event payload", ex);
        }
    }
}