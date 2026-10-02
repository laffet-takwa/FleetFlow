package com.fleetflow.notification.kafka;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import com.fleetflow.common.event.payload.InventoryInsufficientPayload;
import com.fleetflow.common.event.payload.InventoryInsufficientPayload.Shortfall;
import com.fleetflow.common.event.payload.InventoryReservedPayload;
import com.fleetflow.common.event.payload.OrderCreatedPayload;
import com.fleetflow.common.idempotency.IdempotencyService;

import com.fleetflow.notification.config.NotificationProperties;
import com.fleetflow.notification.entity.NotificationLevel;
import com.fleetflow.notification.entity.NotificationType;
import com.fleetflow.notification.service.NotificationDraft;
import com.fleetflow.notification.service.NotificationService;

/**
 * Translates platform events into notifications. This is the only writer of the table:
 * every notification the customer ever sees starts as one of these payloads, so the
 * copy lives here next to the shape of the event that justifies it.
 *
 * <p>The service never calls the Order or Delivery service to render a message - the
 * payloads already carry the order id, the warehouse name, the driver and the vehicle,
 * and a synchronous call would make a notification depend on the availability of
 * another service.
 *
 * <p>Topic names are injected, never hard coded, so an environment can rename a topic
 * without a rebuild.
 */
@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;
    private final IdempotencyService idempotencyService;
    private final NotificationProperties properties;

    public NotificationEventListener(ObjectMapper objectMapper, NotificationService notificationService,
            IdempotencyService idempotencyService, NotificationProperties properties) {
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
        this.idempotencyService = idempotencyService;
        this.properties = properties;
    }

    @KafkaListener(topics = "${fleetflow.kafka.topics.order-created}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onOrderCreated(String json) {
        consume(json, new TypeReference<EventEnvelope<OrderCreatedPayload>>() {}, payload ->
                notificationService.create(payload.customerId(), new NotificationDraft(
                        NotificationType.ORDER_CONFIRMED,
                        NotificationLevel.INFO,
                        "Order #%d confirmed".formatted(payload.orderId()),
                        "Your order #%d has been received and is being prepared.".formatted(payload.orderId()),
                        payload.orderId(),
                        null)));
    }

    @KafkaListener(topics = "${fleetflow.kafka.topics.inventory-reserved}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onInventoryReserved(String json) {
        consume(json, new TypeReference<EventEnvelope<InventoryReservedPayload>>() {}, payload ->
                notificationService.create(payload.customerId(), new NotificationDraft(
                        NotificationType.INVENTORY_RESERVED,
                        NotificationLevel.SUCCESS,
                        "Items reserved",
                        "Your order #%d has been reserved at %s and is ready for dispatch."
                                .formatted(payload.orderId(), payload.warehouseName()),
                        payload.orderId(),
                        null)));
    }

    @KafkaListener(topics = "${fleetflow.kafka.topics.inventory-insufficient}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onInventoryInsufficient(String json) {
        consume(json, new TypeReference<EventEnvelope<InventoryInsufficientPayload>>() {}, payload ->
                notificationService.create(payload.customerId(), new NotificationDraft(
                        NotificationType.INVENTORY_INSUFFICIENT,
                        NotificationLevel.ERROR,
                        "Order #%d cancelled".formatted(payload.orderId()),
                        "We could not fulfil order #%d because %s is out of stock."
                                .formatted(payload.orderId(), productNames(payload.shortfalls())),
                        payload.orderId(),
                        null)));
    }

    @KafkaListener(topics = "${fleetflow.kafka.topics.delivery-assigned}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onDeliveryAssigned(String json) {
        consume(json, new TypeReference<EventEnvelope<DeliveryAssignedPayload>>() {}, payload -> {
            long customerId = payload.customerId();
            notificationService.create(customerId, new NotificationDraft(
                    NotificationType.DRIVER_ASSIGNED,
                    NotificationLevel.INFO,
                    "Driver assigned",
                    "Your order #%d has been assigned to %s (%s).".formatted(
                            payload.orderId(), payload.driverName(), payload.vehicleRegistration()),
                    payload.orderId(),
                    payload.deliveryId()));

            // The operations desk owns dispatch, so it needs to know a delivery is
            // waiting for its driver. Skipped when the customer is the operations
            // account, which would otherwise notify the same person twice.
            long operationsUserId = properties.operationsUserId();
            if (customerId == operationsUserId) {
                return;
            }
            notificationService.create(operationsUserId, new NotificationDraft(
                    NotificationType.DRIVER_ASSIGNED,
                    NotificationLevel.INFO,
                    "New delivery assigned",
                    "Delivery #%d for order #%d is awaiting dispatch."
                            .formatted(payload.deliveryId(), payload.orderId()),
                    payload.orderId(),
                    payload.deliveryId()));
        });
    }

    @KafkaListener(topics = "${fleetflow.kafka.topics.delivery-started}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onDeliveryStarted(String json) {
        consume(json, new TypeReference<EventEnvelope<DeliveryStatusChangedPayload>>() {}, payload ->
                notificationService.create(payload.customerId(), new NotificationDraft(
                        NotificationType.DELIVERY_STARTED,
                        NotificationLevel.INFO,
                        "On the way",
                        "Your delivery for order #%d is on the way.".formatted(payload.orderId()),
                        payload.orderId(),
                        payload.deliveryId())));
    }

    @KafkaListener(topics = "${fleetflow.kafka.topics.delivery-completed}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onDeliveryCompleted(String json) {
        consume(json, new TypeReference<EventEnvelope<DeliveryCompletedPayload>>() {}, payload ->
                notificationService.create(payload.customerId(), new NotificationDraft(
                        NotificationType.DELIVERY_COMPLETED,
                        NotificationLevel.SUCCESS,
                        "Order #%d delivered".formatted(payload.orderId()),
                        "Your order #%d has been delivered. Thank you for choosing FleetFlow!"
                                .formatted(payload.orderId()),
                        payload.orderId(),
                        payload.deliveryId())));
    }

    @KafkaListener(topics = "${fleetflow.kafka.topics.delivery-failed}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onDeliveryFailed(String json) {
        consume(json, new TypeReference<EventEnvelope<DeliveryStatusChangedPayload>>() {}, payload -> {
            // A failed attempt without a reason still needs a sentence, so the optional
            // clause is appended only when the driver actually gave one.
            String reason = payload.reason() == null ? "" : payload.reason();
            notificationService.create(payload.customerId(), new NotificationDraft(
                    NotificationType.DELIVERY_FAILED,
                    NotificationLevel.WARNING,
                    "Delivery problem",
                    "We could not complete the delivery of order #%d. %s"
                            .formatted(payload.orderId(), reason).strip(),
                    payload.orderId(),
                    payload.deliveryId()));
        });
    }

    @KafkaListener(topics = "${fleetflow.kafka.topics.delivery-cancelled}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onDeliveryCancelled(String json) {
        consume(json, new TypeReference<EventEnvelope<DeliveryStatusChangedPayload>>() {}, payload ->
                notificationService.create(payload.customerId(), new NotificationDraft(
                        NotificationType.DELIVERY_CANCELLED,
                        NotificationLevel.WARNING,
                        "Delivery cancelled",
                        "The delivery for order #%d has been cancelled.".formatted(payload.orderId()),
                        payload.orderId(),
                        payload.deliveryId())));
    }

    /**
     * Restores the correlation id of the event and makes the handler run at most once.
     *
     * <p>Kafka delivers at least once, so without {@link IdempotencyService} a
     * redelivered event would show the customer the same notification twice - and the
     * same would happen to the staff copy.
     */
    private <T> void consume(String json, TypeReference<EventEnvelope<T>> envelopeType, Consumer<T> handler) {
        EventEnvelope<T> envelope = read(json, envelopeType);
        try (CorrelationIdScope ignored = CorrelationIdScope.open(envelope.correlationId())) {
            idempotencyService.executeOnce(envelope.eventId(), envelope.eventType(),
                    () -> handler.accept(envelope.payload()));
        }
    }

    private <T> EventEnvelope<T> read(String json, TypeReference<EventEnvelope<T>> envelopeType) {
        try {
            return objectMapper.readValue(json, envelopeType);
        } catch (JsonProcessingException ex) {
            // Thrown unchecked on purpose: the container must not commit the offset of a
            // message it could not understand, otherwise the event is lost for good.
            log.error("Discarding unreadable event payload: {}", ex.getOriginalMessage());
            throw new IllegalStateException("Cannot deserialize the event envelope", ex);
        }
    }

    /** Names the customer recognises; a line is only as useful as the product it names. */
    private static String productNames(List<Shortfall> shortfalls) {
        if (shortfalls == null) {
            return "one of the products";
        }
        String names = shortfalls.stream()
                .map(Shortfall::productName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .collect(Collectors.joining(", "));
        return names.isEmpty() ? "one of the products" : names;
    }
}
