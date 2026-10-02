package com.fleetflow.order.kafka;

import java.util.function.Consumer;

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
import com.fleetflow.common.event.payload.DeliveryAssignedPayload;
import com.fleetflow.common.event.payload.DeliveryCompletedPayload;
import com.fleetflow.common.event.payload.DeliveryStatusChangedPayload;
import com.fleetflow.common.event.payload.InventoryInsufficientPayload;
import com.fleetflow.common.event.payload.InventoryReservedPayload;
import com.fleetflow.common.idempotency.IdempotencyService;

import com.fleetflow.order.service.OrderLifecycleService;

/**
 * Bridges the FleetFlow event bus onto the order lifecycle.
 *
 * <p>Values arrive as JSON strings, so every handler is the same uniform shell:
 * deserialize the envelope, restore the originating correlation id, then let
 * {@link IdempotencyService} guarantee the body runs at most once. The rules themselves
 * live in {@link OrderLifecycleService} and are unit tested there.
 */
@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotencyService;
    private final OrderLifecycleService lifecycleService;

    public OrderEventListener(ObjectMapper objectMapper, IdempotencyService idempotencyService,
            OrderLifecycleService lifecycleService) {

        this.objectMapper = objectMapper;
        this.idempotencyService = idempotencyService;
        this.lifecycleService = lifecycleService;
    }

    @KafkaListener(topics = "${fleetflow.kafka.topics.inventory-reserved}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onInventoryReserved(String json) {
        EventEnvelope<InventoryReservedPayload> envelope =
                read(json, new TypeReference<EventEnvelope<InventoryReservedPayload>>() {
                });
        dispatch(envelope, lifecycleService::onInventoryReserved);
    }

    @KafkaListener(topics = "${fleetflow.kafka.topics.inventory-insufficient}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onInventoryInsufficient(String json) {
        EventEnvelope<InventoryInsufficientPayload> envelope =
                read(json, new TypeReference<EventEnvelope<InventoryInsufficientPayload>>() {
                });
        dispatch(envelope, lifecycleService::onInventoryInsufficient);
    }

    @KafkaListener(topics = "${fleetflow.kafka.topics.delivery-assigned}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onDeliveryAssigned(String json) {
        EventEnvelope<DeliveryAssignedPayload> envelope =
                read(json, new TypeReference<EventEnvelope<DeliveryAssignedPayload>>() {
                });
        dispatch(envelope, lifecycleService::onDeliveryAssigned);
    }

    @KafkaListener(topics = "${fleetflow.kafka.topics.delivery-started}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onDeliveryStarted(String json) {
        EventEnvelope<DeliveryStatusChangedPayload> envelope =
                read(json, new TypeReference<EventEnvelope<DeliveryStatusChangedPayload>>() {
                });
        dispatch(envelope, lifecycleService::onDeliveryStarted);
    }

    @KafkaListener(topics = "${fleetflow.kafka.topics.delivery-completed}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onDeliveryCompleted(String json) {
        EventEnvelope<DeliveryCompletedPayload> envelope =
                read(json, new TypeReference<EventEnvelope<DeliveryCompletedPayload>>() {
                });
        dispatch(envelope, lifecycleService::onDeliveryCompleted);
    }

    @KafkaListener(topics = "${fleetflow.kafka.topics.delivery-failed}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onDeliveryFailed(String json) {
        EventEnvelope<DeliveryStatusChangedPayload> envelope =
                read(json, new TypeReference<EventEnvelope<DeliveryStatusChangedPayload>>() {
                });
        dispatch(envelope, lifecycleService::onDeliveryFailed);
    }

    @KafkaListener(topics = "${fleetflow.kafka.topics.delivery-cancelled}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onDeliveryCancelled(String json) {
        EventEnvelope<DeliveryStatusChangedPayload> envelope =
                read(json, new TypeReference<EventEnvelope<DeliveryStatusChangedPayload>>() {
                });
        dispatch(envelope, lifecycleService::onDeliveryCancelled);
    }

    /**
     * A payload this service cannot read is a contract break, so it is rethrown for the
     * container error handler to deal with rather than being dropped on the floor.
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

    private <T> void dispatch(EventEnvelope<T> envelope, Consumer<T> handler) {
        try (CorrelationIdScope ignored = CorrelationIdScope.open(envelope.correlationId())) {
            idempotencyService.executeOnce(envelope.eventId(), envelope.eventType(),
                    () -> handler.accept(envelope.payload()));
        }
    }
}
