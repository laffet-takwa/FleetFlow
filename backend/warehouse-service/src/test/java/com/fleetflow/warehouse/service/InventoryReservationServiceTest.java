package com.fleetflow.warehouse.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fleetflow.common.event.DomainEventPublisher;
import com.fleetflow.common.event.EventEnvelope;
import com.fleetflow.common.event.payload.InventoryInsufficientPayload;
import com.fleetflow.common.event.payload.InventoryInsufficientPayload.Shortfall;
import com.fleetflow.common.event.payload.InventoryReservedPayload;
import com.fleetflow.common.event.payload.OrderCreatedPayload;

import com.fleetflow.warehouse.client.OrderServiceClient;
import com.fleetflow.warehouse.client.OrderServiceClient.OrderLine;
import com.fleetflow.warehouse.config.KafkaTopicProperties;
import com.fleetflow.warehouse.config.ReservationProperties;
import com.fleetflow.warehouse.entity.Inventory;
import com.fleetflow.warehouse.entity.InventoryReservation;
import com.fleetflow.warehouse.entity.Product;
import com.fleetflow.warehouse.entity.Warehouse;
import com.fleetflow.warehouse.entity.WarehouseStatus;
import com.fleetflow.warehouse.repository.InventoryRepository;
import com.fleetflow.warehouse.repository.InventoryReservationRepository;
import com.fleetflow.warehouse.repository.ProductRepository;

@DisplayName("InventoryReservationService")
@ExtendWith(MockitoExtension.class)
class InventoryReservationServiceTest {

    private static final String TOPIC_RESERVED = "inventory.reserved";
    private static final String TOPIC_INSUFFICIENT = "inventory.insufficient";

    private static final long WAREHOUSE_ID = 1L;
    private static final long ORDER_ID = 500L;
    private static final long CUSTOMER_ID = 8L;

    @Mock
    private ProductRepository productRepository;
    @Mock
    private InventoryRepository inventoryRepository;
    @Mock
    private InventoryReservationRepository reservationRepository;
    @Mock
    private OrderServiceClient orderServiceClient;
    @Mock
    private WarehouseService warehouseService;
    @Mock
    private DomainEventPublisher eventPublisher;

    private InventoryReservationService reservationService;

    @BeforeEach
    void setUp() {
        ReservationProperties reservationProperties = new ReservationProperties();
        reservationProperties.setPreferredWarehouseId(WAREHOUSE_ID);

        KafkaTopicProperties topics = new KafkaTopicProperties();
        topics.setOrderCreated("order.created");
        topics.setInventoryReserved(TOPIC_RESERVED);
        topics.setInventoryInsufficient(TOPIC_INSUFFICIENT);

        reservationService = new InventoryReservationService(productRepository, inventoryRepository,
                reservationRepository, orderServiceClient, warehouseService, reservationProperties, topics,
                eventPublisher);

        when(warehouseService.getById(WAREHOUSE_ID)).thenReturn(warehouse());
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(reservationRepository.save(any(InventoryReservation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ------------------------------------------------------------- happy path

    @Test
    @DisplayName("holding stock moves units from available to reserved and publishes inventory.reserved")
    void reservingLessThanAvailableMovesTheUnits() {
        Product milk = product(1L, "FF-GR-0002", "Carton 6 x Brik UHT 1L", "8.500");
        Product coffee = product(2L, "FF-GR-0004", "Cafe moulu Arabe 250g", "12.750");
        Inventory milkStock = stock(milk, 40, 5);
        Inventory coffeeStock = stock(coffee, 6, 0);

        givenOrder(List.of(new OrderLine(1L, 3), new OrderLine(2L, 2)), milk, coffee);
        givenStock(milkStock, coffeeStock);

        reservationService.reserve(orderCreated());

        assertEquals(37, milkStock.getAvailableQuantity(), "3 units leave the available bucket");
        assertEquals(8, milkStock.getReservedQuantity(), "and join the reserved bucket");
        assertEquals(4, coffeeStock.getAvailableQuantity());
        assertEquals(2, coffeeStock.getReservedQuantity());

        ArgumentCaptor<InventoryReservation> reservations = ArgumentCaptor.forClass(InventoryReservation.class);
        verify(reservationRepository, times(2)).save(reservations.capture());
        assertEquals(List.of(3, 2),
                reservations.getAllValues().stream().map(r -> r.getQuantity()).toList());

        EventEnvelope<InventoryReservedPayload> published = capturedEvent(TOPIC_RESERVED);
        InventoryReservedPayload payload = published.payload();
        assertEquals(TOPIC_RESERVED, published.topic());
        assertEquals(String.valueOf(ORDER_ID), capturedKey(TOPIC_RESERVED),
                "the key is the order id, so every event for one order keeps its ordering");
        assertEquals(ORDER_ID, payload.orderId());
        assertEquals(CUSTOMER_ID, payload.customerId());
        assertEquals(WAREHOUSE_ID, payload.warehouseId());
        assertEquals("Tunis Centre Warehouse", payload.warehouseName());
        assertEquals(5, payload.itemCount(), "itemCount is the total number of units");
        assertEquals(new BigDecimal("50.500"), payload.reservedValue(), "3 x 8.500 + 2 x 12.750");
        verify(eventPublisher, never()).publish(eq(TOPIC_INSUFFICIENT), any(), any());
    }

    @Test
    @DisplayName("a request that exceeds the available stock publishes inventory.insufficient and changes nothing")
    void exceedingAvailablePublishesInsufficient() {
        Product milk = product(1L, "FF-GR-0002", "Carton 6 x Brik UHT 1L", "8.500");
        Inventory milkStock = stock(milk, 2, 1);

        givenOrder(List.of(new OrderLine(1L, 5)), milk);
        givenStock(milkStock);

        reservationService.reserve(orderCreated());

        assertEquals(2, milkStock.getAvailableQuantity(), "available is untouched");
        assertEquals(1, milkStock.getReservedQuantity(), "reserved is untouched");

        InventoryInsufficientPayload payload =
                capturedEvent.<InventoryInsufficientPayload>(TOPIC_INSUFFICIENT).payload();
        assertEquals(ORDER_ID, payload.orderId());
        assertEquals(CUSTOMER_ID, payload.customerId());
        assertEquals(1, payload.shortfalls().size());
        assertEquals(new Shortfall(1L, "Carton 6 x Brik UHT 1L", 5, 2), payload.shortfalls().get(0));

        verify(inventoryRepository, never()).save(any(Inventory.class));
        verify(reservationRepository, never()).save(any(InventoryReservation.class));
        verify(eventPublisher, never()).publish(eq(TOPIC_RESERVED), any(), any());
    }

    @Test
    @DisplayName("one short line rolls the whole reservation back and reports every shortfall")
    void partialFailureReservesNothingAndReportsEveryShortfall() {
        Product ok = product(1L, "FF-GR-0002", "Carton 6 x Brik UHT 1L", "8.500");
        Product thin = product(2L, "FF-GR-0004", "Cafe moulu Arabe 250g", "12.750");
        Product empty = product(3L, "FF-HO-0012", "Coussin coton 50x50", "29.900");

        Inventory okStock = stock(ok, 40, 0);
        Inventory thinStock = stock(thin, 1, 0);
        Inventory emptyStock = stock(empty, 0, 0);

        givenOrder(List.of(new OrderLine(1L, 2), new OrderLine(2L, 5), new OrderLine(3L, 1)),
                ok, thin, empty);
        givenStock(okStock, thinStock, emptyStock);

        reservationService.reserve(orderCreated());

        assertEquals(40, okStock.getAvailableQuantity(), "the reservable line is left alone");
        assertEquals(0, okStock.getReservedQuantity());
        assertEquals(1, thinStock.getAvailableQuantity());
        assertEquals(0, emptyStock.getAvailableQuantity());

        InventoryInsufficientPayload payload =
                capturedEvent.<InventoryInsufficientPayload>(TOPIC_INSUFFICIENT).payload();
        assertEquals(2, payload.shortfalls().size(), "both unfulfillable lines are reported, not just the first");
        assertEquals(List.of(2L, 3L), payload.shortfalls().stream().map(Shortfall::productId).toList());
        assertEquals(new Shortfall(2L, "Cafe moulu Arabe 250g", 5, 1), payload.shortfalls().get(0));
        assertEquals(new Shortfall(3L, "Coussin coton 50x50", 1, 0), payload.shortfalls().get(1));

        verify(inventoryRepository, never()).save(any(Inventory.class));
        verify(reservationRepository, never()).save(any(InventoryReservation.class));
        verify(eventPublisher, never()).publish(eq(TOPIC_RESERVED), any(), any());
    }

    @Test
    @DisplayName("a product the catalogue no longer knows is a shortfall, not a crash")
    void missingProductIsAShortfall() {
        givenOrder(List.of(new OrderLine(404L, 1)));
        when(productRepository.findAllById(any())).thenReturn(List.of());
        when(inventoryRepository.findByWarehouseIdAndProductIdIn(any(), any())).thenReturn(List.of());

        reservationService.reserve(orderCreated());

        InventoryInsufficientPayload payload =
                capturedEvent.<InventoryInsufficientPayload>(TOPIC_INSUFFICIENT).payload();
        assertEquals(1, payload.shortfalls().size());
        assertEquals(404L, payload.shortfalls().get(0).productId());
        assertEquals(0, payload.shortfalls().get(0).available());
        assertTrue(payload.shortfalls().get(0).productName().contains("404"),
                "there is no name to quote, so the id stands in");
        verify(eventPublisher, never()).publish(eq(TOPIC_RESERVED), any(), any());
    }

    // ------------------------------------------------------------ idempotency

    @Test
    @DisplayName("reserving twice for one order publishes inventory.reserved only once")
    void secondReservationForTheSameOrderIsANoOp() {
        Product milk = product(1L, "FF-GR-0002", "Carton 6 x Brik UHT 1L", "8.500");
        Inventory milkStock = stock(milk, 40, 0);

        when(reservationRepository.existsByOrderId(ORDER_ID)).thenReturn(false, true);
        givenOrder(List.of(new OrderLine(1L, 3)), milk);
        givenStock(milkStock);

        reservationService.reserve(orderCreated());
        assertEquals(37, milkStock.getAvailableQuantity());

        reservationService.reserve(orderCreated());

        assertEquals(37, milkStock.getAvailableQuantity(), "the repeat must not take stock twice");
        verify(eventPublisher, times(1)).publish(eq(TOPIC_RESERVED), any(), any());
        verify(reservationRepository, times(1)).save(any(InventoryReservation.class));
        verify(inventoryRepository, times(1)).save(any(Inventory.class));
        verify(orderServiceClient, times(1)).findItemLines(anyLong());
    }

    // ----------------------------------------------------------------- helpers

    private void givenOrder(List<OrderLine> lines, Product... products) {
        when(reservationRepository.existsByOrderId(ORDER_ID)).thenReturn(false);
        when(orderServiceClient.findItemLines(ORDER_ID)).thenReturn(lines);
        when(productRepository.findAllById(any())).thenReturn(List.of(products));
    }

    private void givenStock(Inventory... rows) {
        when(inventoryRepository.findByWarehouseIdAndProductIdIn(eq(WAREHOUSE_ID), any()))
                .thenReturn(List.of(rows));
    }

    @SuppressWarnings("unchecked")
    private <T> EventEnvelope<T> capturedEvent(String topic) {
        ArgumentCaptor<EventEnvelope<T>> envelope = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(eventPublisher).publish(eq(topic), any(), envelope.capture());
        return envelope.getValue();
    }

    private String capturedKey(String topic) {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(eventPublisher).publish(eq(topic), key.capture(), any());
        return key.getValue();
    }

    private static OrderCreatedPayload orderCreated() {
        return new OrderCreatedPayload(ORDER_ID, CUSTOMER_ID, "Tunis", "1000", 5, new BigDecimal("50.500"));
    }

    private static Warehouse warehouse() {
        Warehouse warehouse = new Warehouse();
        warehouse.setId(WAREHOUSE_ID);
        warehouse.setName("Tunis Centre Warehouse");
        warehouse.setCity("Tunis");
        warehouse.setCapacity(50000);
        warehouse.setStatus(WarehouseStatus.ACTIVE);
        return warehouse;
    }

    private static Product product(Long id, String sku, String name, String price) {
        Product product = new Product();
        product.setId(id);
        product.setSku(sku);
        product.setName(name);
        product.setCategory("GROCERY");
        product.setPrice(new BigDecimal(price));
        product.setActive(Boolean.TRUE);
        return product;
    }

    private static Inventory stock(Product product, int available, int reserved) {
        Inventory inventory = new Inventory();
        inventory.setId(product.getId());
        inventory.setWarehouse(warehouse());
        inventory.setProduct(product);
        inventory.setAvailableQuantity(available);
        inventory.setReservedQuantity(reserved);
        return inventory;
    }
}