package com.fleetflow.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.fleetflow.common.event.payload.DeliveryAssignedPayload;
import com.fleetflow.common.event.payload.DeliveryCompletedPayload;
import com.fleetflow.common.event.payload.DeliveryStatusChangedPayload;
import com.fleetflow.common.event.payload.InventoryInsufficientPayload;
import com.fleetflow.common.event.payload.InventoryInsufficientPayload.Shortfall;
import com.fleetflow.common.event.payload.InventoryReservedPayload;

import com.fleetflow.order.entity.Order;
import com.fleetflow.order.entity.OrderStatus;
import com.fleetflow.order.entity.StatusSource;

@DisplayName("OrderLifecycleService event reactions")
class OrderLifecycleServiceTest {

    private static final long ORDER_ID = 42L;
    private static final long CUSTOMER_ID = 8L;
    private static final long DELIVERY_ID = 7L;

    @Mock
    private OrderService orderService;

    private OrderLifecycleService lifecycleService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        lifecycleService = new OrderLifecycleService(orderService);
    }

    @Test
    @DisplayName("inventory.reserved confirms the order")
    void reservedConfirms() {
        Order order = given(OrderStatus.CREATED);

        lifecycleService.onInventoryReserved(reserved());

        assertEquals(OrderStatus.CONFIRMED, order.getStatus());
        List<HistoryRow> timeline = timeline();
        assertEquals(1, timeline.size());
        assertEquals(OrderStatus.CONFIRMED, timeline.get(0).status());
        assertEquals(OrderStatus.CREATED, timeline.get(0).previousStatus());
        assertEquals(StatusSource.SYSTEM, timeline.get(0).source());
    }

    @Test
    @DisplayName("inventory.insufficient cancels the order and records the shortfalls")
    void insufficientCancels() {
        Order order = given(OrderStatus.CREATED);

        lifecycleService.onInventoryInsufficient(new InventoryInsufficientPayload(ORDER_ID, CUSTOMER_ID,
                List.of(new Shortfall(7L, "Carton 6 x Brik UHT 1L", 3, 1),
                        new Shortfall(9L, "Cafe moulu Arabe 250g", 2, 0))));

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertTrue(order.getCancelledReason().startsWith("Insufficient stock:"));
        assertTrue(order.getCancelledReason().contains("Carton 6 x Brik UHT 1L (requested 3, available 1)"));
        assertTrue(order.getCancelledReason().contains("Cafe moulu Arabe 250g (requested 2, available 0)"));
    }

    @Test
    @DisplayName("delivery.assigned stores the delivery id and starts processing")
    void assignedStartsProcessing() {
        Order order = given(OrderStatus.CONFIRMED);

        // driverId is the Delivery Service's local key, driverUserId the platform identity.
        // The Order Service only reads the id and the name, but both must be present.
        lifecycleService.onDeliveryAssigned(new DeliveryAssignedPayload(
                DELIVERY_ID, ORDER_ID, CUSTOMER_ID, 3L, 33L, "Karim Chaabane", 2L, "TN-1234-TN"));

        assertEquals(DELIVERY_ID, order.getDeliveryId());
        assertEquals(OrderStatus.PROCESSING, order.getStatus());
        assertTrue(timeline().get(0).note().contains("Karim Chaabane"));
    }

    @Test
    @DisplayName("delivery.started walks two legal steps and ends out for delivery")
    void startedTakesTwoSteps() {
        Order order = given(OrderStatus.PROCESSING);

        lifecycleService.onDeliveryStarted(statusChanged("IN_TRANSIT", null));

        assertEquals(OrderStatus.OUT_FOR_DELIVERY, order.getStatus());
        List<HistoryRow> timeline = timeline();
        assertEquals(2, timeline.size());
        assertEquals(OrderStatus.READY_FOR_DELIVERY, timeline.get(0).status());
        assertEquals(OrderStatus.PROCESSING, timeline.get(0).previousStatus());
        assertEquals(OrderStatus.OUT_FOR_DELIVERY, timeline.get(1).status());
        assertEquals(OrderStatus.READY_FOR_DELIVERY, timeline.get(1).previousStatus());
    }

    @Test
    @DisplayName("delivery.completed delivers the order")
    void completedDelivers() {
        Order order = given(OrderStatus.OUT_FOR_DELIVERY);

        lifecycleService.onDeliveryCompleted(new DeliveryCompletedPayload(
                DELIVERY_ID, ORDER_ID, CUSTOMER_ID, 3L, 33L, Instant.parse("2026-09-30T11:00:00Z"),
                "Signed by concierge"));

        assertEquals(OrderStatus.DELIVERED, order.getStatus());
        assertTrue(timeline().get(0).note().contains("Signed by concierge"));
    }

    @Test
    @DisplayName("delivery.failed records the reason without moving the order")
    void failedOnlyRecords() {
        Order order = given(OrderStatus.OUT_FOR_DELIVERY);

        lifecycleService.onDeliveryFailed(statusChanged("FAILED", "Customer absent"));

        assertEquals(OrderStatus.OUT_FOR_DELIVERY, order.getStatus());
        List<HistoryRow> timeline = timeline();
        assertEquals(1, timeline.size());
        assertEquals(OrderStatus.OUT_FOR_DELIVERY, timeline.get(0).status());
        assertEquals(StatusSource.SYSTEM, timeline.get(0).source());
        assertTrue(timeline.get(0).note().contains("Customer absent"));
    }

    @Test
    @DisplayName("delivery.cancelled cancels an undelivered order")
    void cancelledCancels() {
        Order order = given(OrderStatus.PROCESSING);

        lifecycleService.onDeliveryCancelled(statusChanged("CANCELLED", "Vehicle breakdown"));

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertTrue(order.getCancelledReason().contains("Vehicle breakdown"));
    }

    @Test
    @DisplayName("delivery.cancelled cannot undo a delivery")
    void cancelledIgnoresDelivered() {
        Order order = given(OrderStatus.DELIVERED);

        lifecycleService.onDeliveryCancelled(statusChanged("CANCELLED", "Too late"));

        assertEquals(OrderStatus.DELIVERED, order.getStatus());
        verify(orderService, never()).appendHistory(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a redelivered event is recorded as ignored instead of failing the record")
    void redeliveryIsTolerated() {
        Order order = given(OrderStatus.DELIVERED);

        lifecycleService.onInventoryReserved(reserved());

        assertEquals(OrderStatus.DELIVERED, order.getStatus());
        List<HistoryRow> timeline = timeline();
        assertEquals(1, timeline.size());
        assertEquals(OrderStatus.DELIVERED, timeline.get(0).status());
        assertTrue(timeline.get(0).note().startsWith("Ignored DELIVERED -> CONFIRMED"));
    }

    @Test
    @DisplayName("an event for an unknown order is dropped, not thrown")
    void unknownOrderIsDropped() {
        when(orderService.findOptionalOrder(ORDER_ID)).thenReturn(Optional.empty());

        lifecycleService.onInventoryReserved(reserved());

        verify(orderService, never()).appendHistory(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a cancellation reason never exceeds the column width")
    void truncatesLongReason() {
        Order order = given(OrderStatus.CREATED);

        lifecycleService.onInventoryInsufficient(new InventoryInsufficientPayload(ORDER_ID, CUSTOMER_ID,
                IntStream.range(0, 40)
                        .mapToObj(index -> new Shortfall((long) index, "A very long product name " + index, 9, 0))
                        .toList()));

        assertEquals(255, order.getCancelledReason().length());
    }

    // ------------------------------------------------------------------- helpers

    private Order given(OrderStatus status) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setCustomerId(CUSTOMER_ID);
        order.setStatus(status);
        when(orderService.findOptionalOrder(ORDER_ID)).thenReturn(Optional.of(order));
        return order;
    }

    private List<HistoryRow> timeline() {
        ArgumentCaptor<OrderStatus> status = ArgumentCaptor.forClass(OrderStatus.class);
        ArgumentCaptor<OrderStatus> previous = ArgumentCaptor.forClass(OrderStatus.class);
        ArgumentCaptor<StatusSource> source = ArgumentCaptor.forClass(StatusSource.class);
        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        verify(orderService, atLeastOnce())
                .appendHistory(any(), status.capture(), previous.capture(), source.capture(), note.capture());

        List<HistoryRow> rows = new ArrayList<>();
        for (int index = 0; index < status.getAllValues().size(); index++) {
            rows.add(new HistoryRow(status.getAllValues().get(index), previous.getAllValues().get(index),
                    source.getAllValues().get(index), note.getAllValues().get(index)));
        }
        return rows;
    }

    private static InventoryReservedPayload reserved() {
        return new InventoryReservedPayload(ORDER_ID, CUSTOMER_ID, 1L, "Tunis hub", null, 2);
    }

    private static DeliveryStatusChangedPayload statusChanged(String newStatus, String reason) {
        return new DeliveryStatusChangedPayload(
            DELIVERY_ID, ORDER_ID, CUSTOMER_ID, 3L, 33L, "ASSIGNED", newStatus, reason);
    }

    private record HistoryRow(OrderStatus status, OrderStatus previousStatus, StatusSource source, String note) {
    }
}
