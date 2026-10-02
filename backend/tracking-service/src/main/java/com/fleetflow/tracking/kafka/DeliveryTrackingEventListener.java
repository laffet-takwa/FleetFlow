package com.fleetflow.tracking.kafka;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.fleetflow.common.correlation.CorrelationIdScope;
import com.fleetflow.common.event.EventEnvelope;
import com.fleetflow.common.event.payload.DeliveryAssignedPayload;
import com.fleetflow.common.event.payload.DeliveryCompletedPayload;
import com.fleetflow.common.event.payload.DeliveryStatusChangedPayload;
import com.fleetflow.tracking.document.DeliveryStatus;
import com.fleetflow.tracking.document.DeliveryTrackingState;
import com.fleetflow.tracking.sse.SseDeliveryRegistry;

/**
 * Mirrors the delivery lifecycle into the tracking projection.
 *
 * <p>{@code IdempotencyService} is deliberately not injected. It is backed by a
 * {@code JdbcTemplate} and this service deliberately runs without a relational database, so
 * the bean is not even available here. Every handler is a write keyed by the delivery id,
 * which makes replaying an event a no-op: re-applying "IN_TRANSIT" to a delivery already in
 * that state leaves the same document behind. The one exception is {@code activatedAt},
 * which the assignment handler only ever sets, so it also survives a replay unchanged.
 */
@Component
public class DeliveryTrackingEventListener {

    private static final Logger log = LoggerFactory.getLogger(DeliveryTrackingEventListener.class);

    private final MongoTemplate mongoTemplate;
    private final SseDeliveryRegistry sseDeliveryRegistry;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DeliveryTrackingEventListener(MongoTemplate mongoTemplate, SseDeliveryRegistry sseDeliveryRegistry,
            ObjectMapper objectMapper, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.sseDeliveryRegistry = sseDeliveryRegistry;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @KafkaListener(topics = "${fleetflow.kafka.topics.delivery-assigned}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onDeliveryAssigned(String json) throws JsonProcessingException {
        EventEnvelope<DeliveryAssignedPayload> envelope = read(json,
                new TypeReference<EventEnvelope<DeliveryAssignedPayload>>() {
                });
        DeliveryAssignedPayload payload = envelope.payload();
        try (CorrelationIdScope ignored = CorrelationIdScope.open(envelope.correlationId())) {
            Instant now = clock.instant();
            // Load first: a redelivered assignment must not reset locationCount or
            // lastLocationAt, which the seeder and the ingestion pipeline own.
            DeliveryTrackingState state = mongoTemplate.findById(payload.deliveryId(),
                    DeliveryTrackingState.class);
            if (state == null) {
                state = DeliveryTrackingState.assign(payload.deliveryId(), payload.orderId(),
                        payload.customerId(), payload.driverId(), payload.driverUserId(), payload.driverName(), now);
            } else {
                state.setOrderId(payload.orderId());
                state.setCustomerId(payload.customerId());
                state.setDriverId(payload.driverId());
                state.setDriverUserId(payload.driverUserId());
                state.setDriverName(payload.driverName());
                state.setActivatedAt(state.getActivatedAt() == null ? now : state.getActivatedAt());
            }
            state.setStatus(DeliveryStatus.ASSIGNED);
            state.setTrackingEnabled(true);
            mongoTemplate.save(state);
            log.info("Tracking activated for delivery {} assigned to driver {} (user {}) [correlationId={}]",
                    payload.deliveryId(), payload.driverId(), payload.driverUserId(), envelope.correlationId());
        }
    }

    @KafkaListener(topics = "${fleetflow.kafka.topics.delivery-picked-up}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onDeliveryPickedUp(String json) throws JsonProcessingException {
        applyInFlightStatus(json);
    }

    @KafkaListener(topics = "${fleetflow.kafka.topics.delivery-started}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onDeliveryStarted(String json) throws JsonProcessingException {
        applyInFlightStatus(json);
    }

    @KafkaListener(topics = "${fleetflow.kafka.topics.delivery-completed}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onDeliveryCompleted(String json) throws JsonProcessingException {
        EventEnvelope<DeliveryCompletedPayload> envelope = read(json,
                new TypeReference<EventEnvelope<DeliveryCompletedPayload>>() {
                });
        DeliveryCompletedPayload payload = envelope.payload();
        try (CorrelationIdScope ignored = CorrelationIdScope.open(envelope.correlationId())) {
            closeTracking(payload.deliveryId(), DeliveryStatus.DELIVERED);
        }
    }

    @KafkaListener(topics = "${fleetflow.kafka.topics.delivery-failed}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onDeliveryFailed(String json) throws JsonProcessingException {
        closeTrackingFromStatusChange(json, DeliveryStatus.FAILED);
    }

    @KafkaListener(topics = "${fleetflow.kafka.topics.delivery-cancelled}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onDeliveryCancelled(String json) throws JsonProcessingException {
        closeTrackingFromStatusChange(json, DeliveryStatus.CANCELLED);
    }

    private void applyInFlightStatus(String json) throws JsonProcessingException {
        EventEnvelope<DeliveryStatusChangedPayload> envelope = read(json,
                new TypeReference<EventEnvelope<DeliveryStatusChangedPayload>>() {
                });
        DeliveryStatusChangedPayload payload = envelope.payload();
        try (CorrelationIdScope ignored = CorrelationIdScope.open(envelope.correlationId())) {
            DeliveryTrackingState state = mongoTemplate.findById(payload.deliveryId(),
                    DeliveryTrackingState.class);
            if (state == null) {
                // The delivery service is the source of truth; if it skipped delivery.assigned
                // there is nothing sensible to track yet, so create the projection here.
                state = DeliveryTrackingState.assign(payload.deliveryId(), payload.orderId(), payload.customerId(),
                        payload.driverId(), payload.driverUserId(), null, clock.instant());
            }
            state.setStatus(payload.newStatus());
            state.setTrackingEnabled(true);
            mongoTemplate.save(state);
            log.info("Delivery {} is now {} [correlationId={}]", payload.deliveryId(), payload.newStatus(),
                    envelope.correlationId());
        }
    }

    private void closeTrackingFromStatusChange(String json, String fallbackStatus) throws JsonProcessingException {
        EventEnvelope<DeliveryStatusChangedPayload> envelope = read(json,
                new TypeReference<EventEnvelope<DeliveryStatusChangedPayload>>() {
                });
        DeliveryStatusChangedPayload payload = envelope.payload();
        try (CorrelationIdScope ignored = CorrelationIdScope.open(envelope.correlationId())) {
            String status = payload.newStatus() == null ? fallbackStatus : payload.newStatus();
            closeTracking(payload.deliveryId(), status);
            if (payload.reason() != null) {
                log.info("Delivery {} ended as {}: {}", payload.deliveryId(), status, payload.reason());
            }
        }
    }

    /**
     * Stops accepting positions and closes the live streams. A browser watching a finished
     * delivery gets a clean end of stream rather than a connection that idles until its
     * timeout, which is what makes a reconnect immediately actionable.
     */
    private void closeTracking(Long deliveryId, String status) {
        DeliveryTrackingState state = mongoTemplate.findById(deliveryId, DeliveryTrackingState.class);
        if (state == null) {
            log.debug("Ignoring terminal event for untracked delivery {}", deliveryId);
            sseDeliveryRegistry.complete(deliveryId);
            return;
        }
        state.setStatus(status);
        state.setTrackingEnabled(false);
        mongoTemplate.save(state);
        sseDeliveryRegistry.complete(deliveryId);
    }

    private <T> EventEnvelope<T> read(String json, TypeReference<EventEnvelope<T>> type) throws JsonProcessingException {
        return objectMapper.readValue(json, type);
    }
}
