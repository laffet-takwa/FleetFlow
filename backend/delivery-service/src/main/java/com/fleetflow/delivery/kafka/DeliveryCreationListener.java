package com.fleetflow.delivery.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.fleetflow.common.correlation.CorrelationIdScope;
import com.fleetflow.common.event.EventEnvelope;
import com.fleetflow.common.event.payload.InventoryReservedPayload;
import com.fleetflow.common.idempotency.IdempotencyService;
import com.fleetflow.delivery.service.DeliveryService;

/**
 * Opens one delivery per reserved order. Kafka is at-least-once, so the handler is
 * wrapped in {@link IdempotencyService#executeOnce} and the {@code order_id} unique
 * constraint is the second line of defence.
 */
@Component
public class DeliveryCreationListener {

    private static final Logger log = LoggerFactory.getLogger(DeliveryCreationListener.class);

    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotencyService;
    private final DeliveryService deliveryService;

    public DeliveryCreationListener(ObjectMapper objectMapper,
            IdempotencyService idempotencyService,
            DeliveryService deliveryService) {
        this.objectMapper = objectMapper;
        this.idempotencyService = idempotencyService;
        this.deliveryService = deliveryService;
    }

    @KafkaListener(topics = "${fleetflow.kafka.topics.inventory-reserved}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onInventoryReserved(String json) throws JsonProcessingException {
        EventEnvelope<InventoryReservedPayload> envelope = objectMapper.readValue(json,
                new TypeReference<EventEnvelope<InventoryReservedPayload>>() {
                });

        try (CorrelationIdScope ignored = CorrelationIdScope.open(envelope.correlationId())) {
            idempotencyService.executeOnce(envelope.eventId(), envelope.eventType(),
                    () -> deliveryService.createFromInventoryReserved(envelope.payload()));
        } catch (RuntimeException ex) {
            log.error("Failed to open a delivery for event {} of type {}: {}", envelope.eventId(),
                    envelope.eventType(), ex.getMessage());
            throw ex;
        }
    }
}
