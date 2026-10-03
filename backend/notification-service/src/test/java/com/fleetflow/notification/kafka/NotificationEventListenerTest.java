package com.fleetflow.notification.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import com.fleetflow.common.correlation.CorrelationId;
import com.fleetflow.common.event.EventEnvelope;
import com.fleetflow.common.event.EventTypes;
import com.fleetflow.common.event.payload.DeliveryAssignedPayload;
import com.fleetflow.common.event.payload.DeliveryCompletedPayload;
import com.fleetflow.common.event.payload.DeliveryStatusChangedPayload;
import com.fleetflow.common.event.payload.InventoryInsufficientPayload;
import com.fleetflow.common.event.payload.InventoryInsufficientPayload.Shortfall;
import com.fleetflow.common.event.payload.InventoryReservedPayload;
import com.fleetflow.common.event.payload.OrderCreatedPayload;
import com.fleetflow.common.idempotency.IdempotencyService;

import com.fleetflow.notification.config.NotificationProperties;
import com.fleetflow.notification.entity.Notification;
import com.fleetflow.notification.entity.NotificationLevel;
import com.fleetflow.notification.entity.NotificationType;
import com.fleetflow.notification.mapper.NotificationMapper;
import com.fleetflow.notification.repository.NotificationRepository;
import com.fleetflow.notification.service.NotificationService;
import com.fleetflow.notification.sse.NotificationSseRegistry;

/**
 * Exercises the event handlers through their real wire format: a JSON envelope in, a
 * notification row out. The repository and the SSE registry are the only mocks, so
 * every assertion is on what this service would actually have written.
 */
@ExtendWith(MockitoExtension.class)
class NotificationEventListenerTest {

    private static final long CUSTOMER_ID = 8L;
    private static final long ORDER_ID = 42L;
    private static final long DELIVERY_ID = 17L;
    private static final String CORRELATION_ID = "7f9a1c2e-0000-4000-8000-00000000abcd";

    @Mock
    private NotificationRepository repository;

    @Mock
    private NotificationSseRegistry sseRegistry;

    @Mock
    private IdempotencyService idempotencyService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AtomicReference<String> correlationIdDuringHandler = new AtomicReference<>();

    private NotificationEventListener listener;

    @BeforeEach
    void setUp() {
        NotificationService notificationService =
                new NotificationService(repository, new NotificationMapper(), sseRegistry);
        NotificationProperties properties = new NotificationProperties();
        listener = new NotificationEventListener(objectMapper, notificationService, idempotencyService, properties);

        AtomicLong sequence = new AtomicLong(400L);
        when(repository.saveAndFlush(any(Notification.class))).thenAnswer(invocation -> {
            Notification saved = invocation.getArgument(0);
            saved.setId(sequence.incrementAndGet());
            saved.setCreatedAt(Instant.parse("2026-10-02T13:41:07.512Z"));
            return saved;
        });

        // Mirrors the real implementation: an event id seen twice runs the action once.
        Set<String> processed = ConcurrentHashMap.newKeySet();
        when(idempotencyService.executeOnce(anyString(), anyString(), any())).thenAnswer(invocation -> {
            correlationIdDuringHandler.set(CorrelationId.get());
            if (!processed.add(invocation.getArgument(0))) {
                return false;
            }
            invocation.<Runnable>getArgument(2).run();
            return true;
        });
    }

    @Test
    void orderCreatedConfirmsTheOrder() throws Exception {
        listener.onOrderCreated(json(EventTypes.ORDER_CREATED, "order.created", new OrderCreatedPayload(
                ORDER_ID, CUSTOMER_ID, "Tunis", "1000", 3, new BigDecimal("412.500"))));

        Notification stored = singleStoredNotification();
        assertThat(stored.getUserId()).isEqualTo(CUSTOMER_ID);
        assertThat(stored.getType()).isEqualTo(NotificationType.ORDER_CONFIRMED);
        assertThat(stored.getLevel()).isEqualTo(NotificationLevel.INFO);
        assertThat(stored.getTitle()).isEqualTo("Order #42 confirmed");
        assertThat(stored.getMessage())
                .isEqualTo("Your order #42 has been received and is being prepared.");
        assertThat(stored.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(stored.getDeliveryId()).isNull();
        assertThat(correlationIdDuringHandler.get()).isEqualTo(CORRELATION_ID);
    }

    @Test
    void inventoryReservedConfirmsTheReservationWithTheWarehouseName() throws Exception {
        listener.onInventoryReserved(json(EventTypes.INVENTORY_RESERVED, "inventory.reserved",
                new InventoryReservedPayload(ORDER_ID, CUSTOMER_ID, 3L, "Tunis Sud warehouse",
                        new BigDecimal("412.500"), 3)));

        Notification stored = singleStoredNotification();
        assertThat(stored.getType()).isEqualTo(NotificationType.INVENTORY_RESERVED);
        assertThat(stored.getLevel()).isEqualTo(NotificationLevel.SUCCESS);
        assertThat(stored.getTitle()).isEqualTo("Items reserved");
        assertThat(stored.getMessage())
                .isEqualTo("Your order #42 has been reserved at Tunis Sud warehouse and is ready for dispatch.");
    }

    @Test
    void inventoryInsufficientNamesTheShortProducts() throws Exception {
        listener.onInventoryInsufficient(json(EventTypes.INVENTORY_INSUFFICIENT, "inventory.insufficient",
                new InventoryInsufficientPayload(ORDER_ID, CUSTOMER_ID, List.of(
                        new Shortfall(5L, "Laptop Pro 14", 2, 1),
                        new Shortfall(6L, "Wireless Mouse", 4, 0)))));

        Notification stored = singleStoredNotification();
        assertThat(stored.getType()).isEqualTo(NotificationType.INVENTORY_INSUFFICIENT);
        assertThat(stored.getLevel()).isEqualTo(NotificationLevel.ERROR);
        assertThat(stored.getTitle()).isEqualTo("Order #42 cancelled");
        assertThat(stored.getMessage()).isEqualTo(
                "We could not fulfil order #42 because Laptop Pro 14, Wireless Mouse is out of stock.");
    }

    @Test
    void deliveryAssignedNotifiesTheCustomerAndTheOperationsDesk() throws Exception {
        listener.onDeliveryAssigned(json(EventTypes.DELIVERY_ASSIGNED, "delivery.assigned",
                new DeliveryAssignedPayload(DELIVERY_ID, ORDER_ID, CUSTOMER_ID, 3L, 33L, "Karim Ben Ali", 2L,
                        "123 Tunis 4521")));

        ArgumentCaptor<Notification> stored = ArgumentCaptor.forClass(Notification.class);
        verify(repository, org.mockito.Mockito.times(2)).saveAndFlush(stored.capture());

        Notification customer = stored.getAllValues().get(0);
        assertThat(customer.getUserId()).isEqualTo(CUSTOMER_ID);
        assertThat(customer.getType()).isEqualTo(NotificationType.DRIVER_ASSIGNED);
        assertThat(customer.getLevel()).isEqualTo(NotificationLevel.INFO);
        assertThat(customer.getTitle()).isEqualTo("Driver assigned");
        assertThat(customer.getMessage())
                .isEqualTo("Your order #42 has been assigned to Karim Ben Ali (123 Tunis 4521).");
        assertThat(customer.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(customer.getDeliveryId()).isEqualTo(DELIVERY_ID);

        Notification operations = stored.getAllValues().get(1);
        assertThat(operations.getUserId()).isEqualTo(new NotificationProperties().operationsUserId());
        assertThat(operations.getTitle()).isEqualTo("New delivery assigned");
        assertThat(operations.getMessage())
                .isEqualTo("Delivery #17 for order #42 is awaiting dispatch.");
        assertThat(operations.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(operations.getDeliveryId()).isEqualTo(DELIVERY_ID);
    }

    @Test
    void theOperationsDeskIsNotNotifiedTwiceWhenItIsTheCustomer() throws Exception {
        listener.onDeliveryAssigned(json(EventTypes.DELIVERY_ASSIGNED, "delivery.assigned",
                new DeliveryAssignedPayload(DELIVERY_ID, ORDER_ID, 2L, 3L, 33L, "Karim Ben Ali", 2L, "123 Tunis 4521")));

        Notification stored = singleStoredNotification();
        assertThat(stored.getUserId()).isEqualTo(2L);
        assertThat(stored.getTitle()).isEqualTo("Driver assigned");
    }

    @Test
    void deliveryStartedTellsTheCustomerTheVanIsRolling() throws Exception {
        listener.onDeliveryStarted(json(EventTypes.DELIVERY_STARTED, "delivery.started",
                deliveryStatusChanged("PICKED_UP", "IN_TRANSIT", null)));

        Notification stored = singleStoredNotification();
        assertThat(stored.getType()).isEqualTo(NotificationType.DELIVERY_STARTED);
        assertThat(stored.getLevel()).isEqualTo(NotificationLevel.INFO);
        assertThat(stored.getTitle()).isEqualTo("On the way");
        assertThat(stored.getMessage()).isEqualTo("Your delivery for order #42 is on the way.");
        assertThat(stored.getDeliveryId()).isEqualTo(DELIVERY_ID);
    }

    @Test
    void deliveryCompletedThanksTheCustomer() throws Exception {
        listener.onDeliveryCompleted(json(EventTypes.DELIVERY_COMPLETED, "delivery.completed",
                new DeliveryCompletedPayload(DELIVERY_ID, ORDER_ID, CUSTOMER_ID, 3L, 33L, Instant.now(), "left with concierge")));

        Notification stored = singleStoredNotification();
        assertThat(stored.getType()).isEqualTo(NotificationType.DELIVERY_COMPLETED);
        assertThat(stored.getLevel()).isEqualTo(NotificationLevel.SUCCESS);
        assertThat(stored.getTitle()).isEqualTo("Order #42 delivered");
        assertThat(stored.getMessage())
                .isEqualTo("Your order #42 has been delivered. Thank you for choosing FleetFlow!");
        assertThat(stored.getDeliveryId()).isEqualTo(DELIVERY_ID);
    }

    @Test
    void deliveryFailedAppendsTheReason() throws Exception {
        listener.onDeliveryFailed(json(EventTypes.DELIVERY_FAILED, "delivery.failed",
                deliveryStatusChanged("IN_TRANSIT", "FAILED", "The recipient was not available.")));

        Notification stored = singleStoredNotification();
        assertThat(stored.getType()).isEqualTo(NotificationType.DELIVERY_FAILED);
        assertThat(stored.getLevel()).isEqualTo(NotificationLevel.WARNING);
        assertThat(stored.getTitle()).isEqualTo("Delivery problem");
        assertThat(stored.getMessage())
                .isEqualTo("We could not complete the delivery of order #42. The recipient was not available.");
    }

    @Test
    void deliveryFailedWithoutAReasonEndsAtTheFullStop() throws Exception {
        listener.onDeliveryFailed(json(EventTypes.DELIVERY_FAILED, "delivery.failed",
                deliveryStatusChanged("IN_TRANSIT", "FAILED", "   ")));

        assertThat(singleStoredNotification().getMessage())
                .isEqualTo("We could not complete the delivery of order #42.");
    }

    @Test
    void deliveryCancelledWarnsTheCustomer() throws Exception {
        listener.onDeliveryCancelled(json(EventTypes.DELIVERY_CANCELLED, "delivery.cancelled",
                deliveryStatusChanged("ASSIGNED", "CANCELLED", "The order was cancelled")));

        Notification stored = singleStoredNotification();
        assertThat(stored.getType()).isEqualTo(NotificationType.DELIVERY_CANCELLED);
        assertThat(stored.getLevel()).isEqualTo(NotificationLevel.WARNING);
        assertThat(stored.getTitle()).isEqualTo("Delivery cancelled");
        assertThat(stored.getMessage()).isEqualTo("The delivery for order #42 has been cancelled.");
    }

    @Test
    void aRedeliveredEventCreatesOnlyOneNotification() throws Exception {
        String message = json("evt-1", EventTypes.ORDER_CREATED, "order.created", new OrderCreatedPayload(
                ORDER_ID, CUSTOMER_ID, "Tunis", "1000", 3, new BigDecimal("412.500")));

        listener.onOrderCreated(message);
        listener.onOrderCreated(message);

        verify(repository).saveAndFlush(any(Notification.class));
        verify(sseRegistry).push(org.mockito.ArgumentMatchers.eq(CUSTOMER_ID), any());
    }

    @Test
    // The malformed message dies in the deserializer, so the shared stubs of setUp are
    // never touched and strict stubbing would flag them.
    @MockitoSettings(strictness = Strictness.LENIENT)
    void anUnreadablePayloadIsRejectedSoTheOffsetIsNotCommitted() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> listener.onOrderCreated("{\"eventId\": \"evt-1\", "));

        verify(repository, never()).saveAndFlush(any(Notification.class));
        verify(idempotencyService, never()).executeOnce(anyString(), anyString(), any());
    }

    private Notification singleStoredNotification() {
        ArgumentCaptor<Notification> stored = ArgumentCaptor.forClass(Notification.class);
        verify(repository).saveAndFlush(stored.capture());
        return stored.getValue();
    }

    private static DeliveryStatusChangedPayload deliveryStatusChanged(String previous, String next, String reason) {
        return new DeliveryStatusChangedPayload(DELIVERY_ID, ORDER_ID, CUSTOMER_ID, 3L, 33L, previous, next, reason);
    }

    private String json(String eventType, String topic, Object payload) throws Exception {
        return json("evt-" + eventType, eventType, topic, payload);
    }

    private String json(String eventId, String eventType, String topic, Object payload) throws Exception {
        return objectMapper.writeValueAsString(new EventEnvelope<>(
                eventId, eventType, topic, CORRELATION_ID, Instant.now(), payload));
    }
}
