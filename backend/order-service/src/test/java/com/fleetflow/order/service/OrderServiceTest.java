package com.fleetflow.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fleetflow.common.event.DomainEventPublisher;
import com.fleetflow.common.event.EventEnvelope;
import com.fleetflow.common.event.EventTypes;
import com.fleetflow.common.event.KafkaTopics;
import com.fleetflow.common.event.payload.OrderCreatedPayload;
import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.exception.InvalidStateTransitionException;
import com.fleetflow.common.exception.ResourceNotFoundException;
import com.fleetflow.common.security.FleetRole;
import com.fleetflow.common.security.JwtPrincipal;

import com.fleetflow.order.client.ProductCatalogClient;
import com.fleetflow.order.client.ProductCatalogClient.ProductSnapshot;
import com.fleetflow.order.config.PricingProperties;
import com.fleetflow.order.dto.CancelOrderRequest;
import com.fleetflow.order.dto.CreateOrderRequest;
import com.fleetflow.order.dto.CreateOrderRequest.OrderLineRequest;
import com.fleetflow.order.dto.OrderResponse;
import com.fleetflow.order.dto.UpdateOrderStatusRequest;
import com.fleetflow.order.entity.Order;
import com.fleetflow.order.entity.OrderItem;
import com.fleetflow.order.entity.OrderStatus;
import com.fleetflow.order.entity.OrderStatusHistory;
import com.fleetflow.order.entity.StatusSource;
import com.fleetflow.order.mapper.OrderMapper;
import com.fleetflow.order.repository.OrderItemRepository;
import com.fleetflow.order.repository.OrderRepository;
import com.fleetflow.order.repository.OrderStatusHistoryRepository;

@DisplayName("OrderService")
class OrderServiceTest {

    private static final long CUSTOMER_ID = 8L;
    private static final long OTHER_CUSTOMER_ID = 12L;
    private static final long GENERATED_ID = 100L;

    private static final ProductSnapshot MILK = new ProductSnapshot(7L, "MILK-1L", "Carton 6 x Brik UHT 1L",
            new BigDecimal("12.500"), true, "Groceries");
    private static final ProductSnapshot COFFEE = new ProductSnapshot(9L, "COF-250", "Cafe moulu Arabe 250g",
            new BigDecimal("3.250"), true, "Groceries");
    private static final ProductSnapshot DISCONTINUED = new ProductSnapshot(11L, "OLD-1", "Jus concentre 1L",
            new BigDecimal("4.800"), false, "Groceries");

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private OrderStatusHistoryRepository historyRepository;
    @Mock
    private ProductCatalogClient productCatalogClient;
    @Mock
    private DomainEventPublisher eventPublisher;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        PricingProperties pricing = new PricingProperties();
        pricing.setDeliveryFee(new BigDecimal("8.00"));
        pricing.setCurrency("TND");
        orderService = new OrderService(orderRepository, orderItemRepository, historyRepository,
                productCatalogClient, eventPublisher, new OrderSortResolver(), new OrderMapper(), pricing);

        // The real persistence callbacks do not run without a session, so the stubs
        // stand in for the generated id and the @PrePersist timestamps.
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(GENERATED_ID);
            }
            if (saved.getCreatedAt() == null) {
                saved.setCreatedAt(Instant.parse("2026-09-28T10:15:30Z"));
            }
            saved.setUpdatedAt(saved.getCreatedAt());
            return saved;
        });
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(historyRepository.save(any(OrderStatusHistory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(orderItemRepository.sumQuantitiesByOrderIds(anyCollection()))
                .thenAnswer(invocation -> List.of());

        authenticate(CUSTOMER_ID, FleetRole.CUSTOMER);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ checkout

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("prices the basket server side and publishes order.created")
        void pricesServerSideAndPublishes() {
            when(productCatalogClient.findByIds(anyCollection())).thenReturn(List.of(MILK, COFFEE));

            OrderResponse response = orderService.create(request(List.of(line(7L, 2), line(9L, 3))));

            // 2 x 12.500 + 3 x 3.250 = 34.750, plus the 8.000 delivery fee.
            assertEquals(new BigDecimal("34.750"), response.subtotal());
            assertEquals(new BigDecimal("8.000"), response.deliveryFee());
            assertEquals(new BigDecimal("42.750"), response.totalAmount());
            assertEquals("TND", response.currency());
            assertEquals("CREATED", response.status());
            assertEquals(CUSTOMER_ID, response.customerId());
            assertEquals(GENERATED_ID, response.id());
            assertEquals(5, response.itemCount());
            assertEquals(2, response.items().size());
            assertEquals("Carton 6 x Brik UHT 1L", response.items().get(0).productName());
            assertEquals(new BigDecimal("25.000"), response.items().get(0).lineSubtotal());
            assertEquals("Tunis", response.city());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<EventEnvelope<OrderCreatedPayload>> captor = ArgumentCaptor.forClass(EventEnvelope.class);
            verify(eventPublisher).publish(eq(KafkaTopics.ORDER_CREATED), eq(String.valueOf(GENERATED_ID)),
                    captor.capture());

            EventEnvelope<OrderCreatedPayload> envelope = captor.getValue();
            assertEquals(EventTypes.ORDER_CREATED, envelope.eventType());
            assertEquals(KafkaTopics.ORDER_CREATED, envelope.topic());
            assertNotNull(envelope.eventId());
            OrderCreatedPayload payload = envelope.payload();
            assertEquals(GENERATED_ID, payload.orderId());
            assertEquals(CUSTOMER_ID, payload.customerId());
            assertEquals("Tunis", payload.city());
            assertEquals("1000", payload.postalCode());
            assertEquals(5, payload.itemCount());
            assertEquals(new BigDecimal("42.750"), payload.totalAmount());
        }

        @Test
        @DisplayName("opens the timeline with a CUSTOMER sourced CREATED row")
        void writesInitialHistoryRow() {
            when(productCatalogClient.findByIds(anyCollection())).thenReturn(List.of(MILK));

            orderService.create(request(List.of(line(7L, 2))));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<OrderStatusHistory> captor = ArgumentCaptor.forClass(OrderStatusHistory.class);
            verify(historyRepository).save(captor.capture());

            OrderStatusHistory history = captor.getValue();
            assertEquals(OrderStatus.CREATED, history.getStatus());
            assertNull(history.getPreviousStatus());
            assertEquals(StatusSource.CUSTOMER, history.getSource());
            assertNotNull(history.getChangedAt());
            assertEquals(GENERATED_ID, history.getOrder().getId());
        }

        @Test
        @DisplayName("rejects an empty item list")
        void rejectsEmptyItemList() {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.create(request(List.of())));

            assertEquals(ErrorCode.VALIDATION_FAILED, ex.getErrorCode());
            verify(productCatalogClient, never()).findByIds(anyCollection());
            verify(eventPublisher, never()).publish(any(), any(), any());
        }

        @Test
        @DisplayName("rejects the same product on two lines")
        void rejectsDuplicateProducts() {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.create(request(List.of(line(7L, 1), line(7L, 4)))));

            assertEquals(ErrorCode.VALIDATION_FAILED, ex.getErrorCode());
            assertTrue(ex.getMessage().contains("more than one line"));
        }

        @Test
        @DisplayName("rejects a non positive quantity even if validation was bypassed")
        void rejectsNonPositiveQuantity() {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.create(request(List.of(line(7L, 0)))));

            assertEquals(ErrorCode.VALIDATION_FAILED, ex.getErrorCode());
        }

        @Test
        @DisplayName("rejects an inactive product as unprocessable")
        void rejectsInactiveProduct() {
            when(productCatalogClient.findByIds(anyCollection())).thenReturn(List.of(DISCONTINUED));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.create(request(List.of(line(11L, 1)))));

            assertEquals(ErrorCode.UNPROCESSABLE, ex.getErrorCode());
            assertTrue(ex.getMessage().contains("Jus concentre 1L is no longer available"));
            verify(eventPublisher, never()).publish(any(), any(), any());
        }

        @Test
        @DisplayName("rejects a product the catalogue does not know")
        void rejectsUnknownProduct() {
            when(productCatalogClient.findByIds(anyCollection())).thenReturn(List.of(COFFEE));

            ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                    () -> orderService.create(request(List.of(line(7L, 1), line(9L, 1)))));

            assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
            assertTrue(ex.getMessage().contains("Product 7"));
            verify(eventPublisher, never()).publish(any(), any(), any());
        }

        @Test
        @DisplayName("surfaces an unreachable warehouse as 503 rather than a 500")
        void surfacesUnreachableWarehouse() {
            when(productCatalogClient.findByIds(anyCollection()))
                    .thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                            "warehouse-service is unreachable"));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.create(request(List.of(line(7L, 1)))));

            assertEquals(ErrorCode.SERVICE_UNAVAILABLE, ex.getErrorCode());
        }
    }

    // ------------------------------------------------------------------ ownership

    @Nested
    @DisplayName("ownership")
    class Ownership {

        @Test
        @DisplayName("a customer cannot read another customer's order")
        void customerCannotReadForeignOrder() {
            Order order = order(42L, OTHER_CUSTOMER_ID, OrderStatus.CREATED);
            when(orderRepository.findById(42L)).thenReturn(Optional.of(order));

            BusinessException ex = assertThrows(BusinessException.class, () -> orderService.getById(42L));

            assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        }

        @Test
        @DisplayName("staff may read any order")
        void staffMayReadAnyOrder() {
            authenticate(2L, FleetRole.OPERATIONS);
            Order order = order(42L, OTHER_CUSTOMER_ID, OrderStatus.CONFIRMED);
            when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
            stubDescribe(42L, List.of(), List.of(), 0L);

            OrderResponse response = orderService.getById(42L);

            assertEquals(42L, response.id());
        }

        @Test
        @DisplayName("a customer may read their own order")
        void customerMayReadOwnOrder() {
            Order order = order(42L, CUSTOMER_ID, OrderStatus.CREATED);
            when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
            stubDescribe(42L, List.of(), List.of(), 0L);

            assertEquals(42L, orderService.getById(42L).id());
        }
    }

    // ---------------------------------------------------------------- cancellation

    @Nested
    @DisplayName("cancel")
    class Cancel {

        @ParameterizedTest(name = "a customer may cancel from {0}")
        @CsvSource({ "CREATED", "CONFIRMED" })
        @DisplayName("a customer may cancel before dispatch processing starts")
        void customerCanCancelEarly(OrderStatus from) {
            Order order = order(42L, CUSTOMER_ID, from);
            when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
            stubDescribe(42L, List.of(), List.of(), 0L);

            OrderResponse response = orderService.cancel(42L, new CancelOrderRequest("Changed my mind"));

            assertEquals("CANCELLED", response.status());
            assertEquals("Changed my mind", response.cancelledReason());
            verify(historyRepository).save(any(OrderStatusHistory.class));
        }

        @Test
        @DisplayName("a customer cannot cancel from PROCESSING")
        void customerCannotCancelFromProcessing() {
            Order order = order(42L, CUSTOMER_ID, OrderStatus.PROCESSING);
            when(orderRepository.findById(42L)).thenReturn(Optional.of(order));

            InvalidStateTransitionException ex = assertThrows(InvalidStateTransitionException.class,
                    () -> orderService.cancel(42L, new CancelOrderRequest(null)));

            assertEquals(ErrorCode.INVALID_STATE_TRANSITION, ex.getErrorCode());
            assertTrue(ex.getMessage().contains("PROCESSING to CANCELLED"));
            assertEquals(OrderStatus.PROCESSING, order.getStatus());
        }

        @Test
        @DisplayName("staff can cancel from PROCESSING and the row is OPERATIONS sourced")
        void staffCanCancelFromProcessing() {
            authenticate(2L, FleetRole.OPERATIONS);
            Order order = order(42L, CUSTOMER_ID, OrderStatus.PROCESSING);
            when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
            stubDescribe(42L, List.of(), List.of(), 0L);

            OrderResponse response = orderService.cancel(42L, null);

            assertEquals("CANCELLED", response.status());
            assertEquals("Cancelled by operations", response.cancelledReason());

            ArgumentCaptor<OrderStatusHistory> captor = ArgumentCaptor.forClass(OrderStatusHistory.class);
            verify(historyRepository).save(captor.capture());
            assertEquals(StatusSource.OPERATIONS, captor.getValue().getSource());
            assertEquals(OrderStatus.PROCESSING, captor.getValue().getPreviousStatus());
        }

        @ParameterizedTest(name = "cancelling from {0} is refused")
        @CsvSource({ "READY_FOR_DELIVERY", "OUT_FOR_DELIVERY", "DELIVERED", "CANCELLED" })
        @DisplayName("cancelling after processing has begun is a 409")
        void cannotCancelTooLate(OrderStatus from) {
            Order order = order(42L, CUSTOMER_ID, from);
            when(orderRepository.findById(42L)).thenReturn(Optional.of(order));

            assertThrows(InvalidStateTransitionException.class, () -> orderService.cancel(42L, null));
            assertEquals(from, order.getStatus());
        }
    }

    // -------------------------------------------------------------- manual status

    @Nested
    @DisplayName("updateStatus")
    class UpdateStatus {

        @Test
        @DisplayName("promotes a confirmed order to processing")
        void promotesConfirmedOrder() {
            Order order = order(42L, CUSTOMER_ID, OrderStatus.CONFIRMED);
            when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
            stubDescribe(42L, List.of(), List.of(), 0L);

            OrderResponse response = orderService.updateStatus(42L,
                    new UpdateOrderStatusRequest(OrderStatus.PROCESSING, "Picked and packed"));

            assertEquals("PROCESSING", response.status());
            ArgumentCaptor<OrderStatusHistory> captor = ArgumentCaptor.forClass(OrderStatusHistory.class);
            verify(historyRepository).save(captor.capture());
            assertEquals(StatusSource.OPERATIONS, captor.getValue().getSource());
            assertEquals("Picked and packed", captor.getValue().getNote());
        }

        @ParameterizedTest(name = "{0} -> DELIVERED is refused by hand")
        @CsvSource({ "CREATED", "CONFIRMED", "PROCESSING", "READY_FOR_DELIVERY" })
        @DisplayName("delivery is not reachable except from OUT_FOR_DELIVERY")
        void refusesManualDelivery(OrderStatus from) {
            Order order = order(42L, CUSTOMER_ID, from);
            when(orderRepository.findById(42L)).thenReturn(Optional.of(order));

            InvalidStateTransitionException ex = assertThrows(InvalidStateTransitionException.class,
                    () -> orderService.updateStatus(42L, new UpdateOrderStatusRequest(OrderStatus.DELIVERED, null)));

            assertEquals(ErrorCode.INVALID_STATE_TRANSITION, ex.getErrorCode());
            assertEquals(from, order.getStatus());
        }

        @Test
        @DisplayName("a delivered order cannot be cancelled")
        void refusesCancellingDelivered() {
            Order order = order(42L, CUSTOMER_ID, OrderStatus.DELIVERED);
            when(orderRepository.findById(42L)).thenReturn(Optional.of(order));

            assertThrows(InvalidStateTransitionException.class,
                    () -> orderService.updateStatus(42L, new UpdateOrderStatusRequest(OrderStatus.CANCELLED, null)));
        }
    }

    // ------------------------------------------------------------------- helpers

    private void stubDescribe(long orderId, List<OrderItem> items,
            List<OrderStatusHistory> timeline, long itemCount) {

        when(orderItemRepository.findByOrderIdOrderByIdAsc(orderId)).thenReturn(items);
        when(historyRepository.findByOrderIdOrderByChangedAtAscIdAsc(orderId)).thenReturn(timeline);
        when(orderItemRepository.sumQuantitiesByOrderIds(anyCollection()))
                .thenReturn(List.of(new ItemCount(orderId, itemCount)));
    }

    private static void authenticate(Long userId, FleetRole role) {
        JwtPrincipal principal = new JwtPrincipal(userId, "user" + userId + "@fleetflow.local", role);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority(role.authority()))));
    }

    private static CreateOrderRequest request(List<OrderLineRequest> lines) {
        return new CreateOrderRequest(lines, "12 Rue Habib Bourguiba", "Tunis", "1000");
    }

    private static OrderLineRequest line(Long productId, int quantity) {
        return new OrderLineRequest(productId, quantity);
    }

    private static Order order(Long id, Long customerId, OrderStatus status) {
        Order order = new Order();
        order.setId(id);
        order.setCustomerId(customerId);
        order.setStatus(status);
        order.setSubtotal(new BigDecimal("10.000"));
        order.setDeliveryFee(new BigDecimal("8.000"));
        order.setTotalAmount(new BigDecimal("18.000"));
        order.setCurrency("TND");
        order.setDeliveryAddress("12 Rue Habib Bourguiba");
        order.setCity("Tunis");
        order.setPostalCode("1000");
        order.setCreatedAt(Instant.parse("2026-09-28T10:15:30Z"));
        order.setUpdatedAt(Instant.parse("2026-09-28T10:15:30Z"));
        return order;
    }

    private record ItemCount(Long orderId, Long itemCount) implements OrderItemRepository.ItemCountView {

        @Override
        public Long getOrderId() {
            return orderId;
        }

        @Override
        public Long getItemCount() {
            return itemCount;
        }
    }
}
