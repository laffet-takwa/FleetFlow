package com.fleetflow.warehouse.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fleetflow.common.correlation.CorrelationId;
import com.fleetflow.common.event.EventTypes;
import com.fleetflow.common.event.payload.InventoryInsufficientPayload;
import com.fleetflow.common.event.payload.InventoryInsufficientPayload.Shortfall;
import com.fleetflow.common.event.payload.InventoryReservedPayload;
import com.fleetflow.common.event.payload.OrderCreatedPayload;
import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;

import com.fleetflow.warehouse.client.OrderServiceClient;
import com.fleetflow.warehouse.client.OrderServiceClient.OrderLine;
import com.fleetflow.warehouse.config.KafkaTopicProperties;
import com.fleetflow.warehouse.config.ReservationProperties;
import com.fleetflow.warehouse.entity.Inventory;
import com.fleetflow.warehouse.entity.InventoryReservation;
import com.fleetflow.warehouse.entity.Product;
import com.fleetflow.warehouse.entity.Warehouse;
import com.fleetflow.warehouse.kafka.InventoryEventPublisher;
import com.fleetflow.warehouse.repository.InventoryRepository;
import com.fleetflow.warehouse.repository.InventoryReservationRepository;
import com.fleetflow.warehouse.repository.ProductRepository;

@Service
public class InventoryReservationService {

    private static final Logger log = LoggerFactory.getLogger(InventoryReservationService.class);

    private static final int MONEY_SCALE = 3;

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryReservationRepository reservationRepository;
    private final OrderServiceClient orderServiceClient;
    private final WarehouseService warehouseService;
    private final ReservationProperties reservationProperties;
    private final KafkaTopicProperties topics;
    private final ApplicationEventPublisher eventPublisher;

    public InventoryReservationService(ProductRepository productRepository,
            InventoryRepository inventoryRepository,
            InventoryReservationRepository reservationRepository,
            OrderServiceClient orderServiceClient,
            WarehouseService warehouseService,
            ReservationProperties reservationProperties,
            KafkaTopicProperties topics,
            DomainEventPublisher eventPublisher) {

        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
        this.reservationRepository = reservationRepository;
        this.orderServiceClient = orderServiceClient;
        this.warehouseService = warehouseService;
        this.reservationProperties = reservationProperties;
        this.topics = topics;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Holds stock for every line of an order, all lines or none.
     *
     * <p>The whole decision is taken before the first write: a partly reserved order
     * would leave the warehouse physically inconsistent with what the customer was told,
     * so shortfalls short-circuit into {@code inventory.insufficient} instead.
     */
    @Transactional
    public void reserve(OrderCreatedPayload event) {
        Long orderId = event.orderId();
        Long customerId = event.customerId();

        if (reservationRepository.existsByOrderId(orderId)) {
            log.info("Order {} already holds a reservation, ignoring the repeat [correlationId={}]", orderId,
                    CorrelationId.getOrCreate());
            return;
        }

        List<OrderLine> lines = orderServiceClient.findItemLines(orderId);
        if (lines.isEmpty()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    "Order " + orderId + " has no lines to reserve");
        }

        Warehouse warehouse = warehouseService.getById(reservationProperties.getPreferredWarehouseId());
        List<LinePlan> plans = plan(orderId, lines, warehouse);

        List<Shortfall> shortfalls = plans.stream()
                .filter(LinePlan::isShort)
                .map(LinePlan::shortfall)
                .toList();
        if (!shortfalls.isEmpty()) {
            defer(topics.getInventoryInsufficient(), orderId, EventTypes.INVENTORY_INSUFFICIENT,
                    new InventoryInsufficientPayload(orderId, customerId, shortfalls));
            log.warn("Order {} cannot be reserved, {} of {} lines short [correlationId={}]", orderId,
                    shortfalls.size(), plans.size(), CorrelationId.getOrCreate());
            return;
        }

        BigDecimal reservedValue = BigDecimal.ZERO;
        int itemCount = 0;
        for (LinePlan plan : plans) {
            plan.inventory().reserve(plan.quantity());
            inventoryRepository.save(plan.inventory());
            reservationRepository.save(
                    new InventoryReservation(orderId, plan.product(), warehouse, plan.quantity()));
            reservedValue = reservedValue.add(plan.product().getPrice()
                    .multiply(BigDecimal.valueOf(plan.quantity())));
            itemCount += plan.quantity();
        }

        defer(topics.getInventoryReserved(), orderId, EventTypes.INVENTORY_RESERVED,
                new InventoryReservedPayload(orderId, customerId, warehouse.getId(), warehouse.getName(),
                        reservedValue.setScale(MONEY_SCALE, RoundingMode.HALF_UP), itemCount));
        log.info("Reserved {} units across {} lines for order {} from warehouse {} [correlationId={}]", itemCount,
                plans.size(), orderId, warehouse.getId(), CorrelationId.getOrCreate());
    }

    // ------------------------------------------------------------------ planning

    /**
     * Loads everything the decision needs without touching a single quantity, so the
     * caller can inspect the whole picture before committing to it. The stock rows are
     * read {@code FOR UPDATE} so the availability decided on here is still the
     * availability that is written, even under a concurrent reservation.
     */
    private List<LinePlan> plan(Long orderId, List<OrderLine> lines, Warehouse warehouse) {
        List<Long> productIds = new ArrayList<>(new LinkedHashSet<>(
                lines.stream().map(OrderLine::productId).toList()));
        Map<Long, Product> products = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, Inventory> stock = inventoryRepository
                .findByWarehouseIdAndProductIdInOrderByIdAsc(warehouse.getId(), productIds).stream()
                .collect(Collectors.toMap(
                        inventory -> inventory.getProduct().getId(),
                        Function.identity(),
                        (first, second) -> first));

        List<LinePlan> plans = new ArrayList<>(lines.size());
        for (OrderLine line : lines) {
            if (line.productId() == null || line.quantity() == null || line.quantity() < 1) {
                throw new BusinessException(ErrorCode.UNPROCESSABLE,
                        "Order " + orderId + " carries a line without a product or a positive quantity");
            }
            plans.add(new LinePlan(line.productId(),
                    products.get(line.productId()),
                    stock.get(line.productId()),
                    line.quantity()));
        }
        return plans;
    }

    /** One line's verdict: reservable, or the shortfall the customer needs to hear about. */
    private record LinePlan(Long productId, Product product, Inventory inventory, int quantity) {

        boolean isShort() {
            return product == null || available() < quantity;
        }

        int available() {
            return inventory == null ? 0 : inventory.getAvailableQuantity();
        }

        Shortfall shortfall() {
            // A product the catalogue no longer knows has no name to quote, so the id
            // stands in rather than inventing one.
            String productName = product == null ? "Product " + productId : product.getName();
            return new Shortfall(productId, productName, quantity, available());
        }
    }

    /**
     * Records the decision to publish under the order id as the key, so every event for
     * one order lands on the same partition and subscribers cannot see
     * {@code insufficient} before the {@code order.created} that caused it. The send
     * itself happens in {@link InventoryEventPublisher}, after the commit.
     */
    private void defer(String topic, Long orderId, String eventType, Object payload) {
        eventPublisher.publishEvent(new InventoryEventPublisher.Intent(topic, orderId, eventType, payload));
    }
}