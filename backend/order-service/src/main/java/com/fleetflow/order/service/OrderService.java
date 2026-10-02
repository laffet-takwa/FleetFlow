package com.fleetflow.order.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fleetflow.common.api.PageResponse;
import com.fleetflow.common.correlation.CorrelationId;
import com.fleetflow.common.event.DomainEventPublisher;
import com.fleetflow.common.event.EventEnvelope;
import com.fleetflow.common.event.EventTypes;
import com.fleetflow.common.event.KafkaTopics;
import com.fleetflow.common.event.payload.OrderCreatedPayload;
import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.exception.InvalidStateTransitionException;
import com.fleetflow.common.exception.ResourceNotFoundException;
import com.fleetflow.common.security.SecurityUtils;

import com.fleetflow.order.client.ProductCatalogClient;
import com.fleetflow.order.client.ProductCatalogClient.ProductSnapshot;
import com.fleetflow.order.config.PricingProperties;
import com.fleetflow.order.dto.CancelOrderRequest;
import com.fleetflow.order.dto.CreateOrderRequest;
import com.fleetflow.order.dto.CreateOrderRequest.OrderLineRequest;
import com.fleetflow.order.dto.OrderItemLineResponse;
import com.fleetflow.order.dto.OrderResponse;
import com.fleetflow.order.dto.OrderSummaryResponse;
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

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final int MAX_PAGE_SIZE = 200;
    private static final int MONEY_SCALE = 3;
    /** How far back the list endpoint looks when the caller supplies no date range. */
    private static final int DEFAULT_LOOKBACK_DAYS = 365;
    /** Both cancelled_reason and history.note are VARCHAR(255). */
    private static final int MAX_TEXT_LENGTH = 255;

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final ProductCatalogClient productCatalogClient;
    private final DomainEventPublisher eventPublisher;
    private final OrderSortResolver sortResolver;
    private final OrderMapper mapper;
    private final PricingProperties pricing;

    public OrderService(OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            OrderStatusHistoryRepository historyRepository,
            ProductCatalogClient productCatalogClient,
            DomainEventPublisher eventPublisher,
            OrderSortResolver sortResolver,
            OrderMapper mapper,
            PricingProperties pricing) {

        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.historyRepository = historyRepository;
        this.productCatalogClient = productCatalogClient;
        this.eventPublisher = eventPublisher;
        this.sortResolver = sortResolver;
        this.mapper = mapper;
        this.pricing = pricing;
    }

    // ------------------------------------------------------------------ checkout

    /**
     * Prices and stores an order entirely server side: nothing the client sent about
     * money is trusted, and the catalogue snapshot is what the order is permanently
     * bound to.
     */
    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        Long customerId = SecurityUtils.requireUserId();
        List<OrderLineRequest> lines = requireDistinctLines(request.items());
        Map<Long, ProductSnapshot> catalogue = loadCatalogue(lines);

        List<OrderItem> items = new ArrayList<>(lines.size());
        BigDecimal subtotal = BigDecimal.ZERO;
        for (OrderLineRequest line : lines) {
            ProductSnapshot product = catalogue.get(line.productId());
            if (!Boolean.TRUE.equals(product.active())) {
                throw new BusinessException(ErrorCode.UNPROCESSABLE,
                        "Product " + product.name() + " is no longer available");
            }
            BigDecimal unitPrice = money(product.price());
            BigDecimal lineSubtotal = unitPrice.multiply(BigDecimal.valueOf(line.quantity()));
            subtotal = subtotal.add(lineSubtotal);

            OrderItem item = new OrderItem();
            item.setProductId(product.id());
            item.setProductName(product.name());
            item.setQuantity(line.quantity());
            item.setUnitPrice(unitPrice);
            item.setLineSubtotal(lineSubtotal);
            items.add(item);
        }

        BigDecimal deliveryFee = money(pricing.getDeliveryFee());
        BigDecimal totalAmount = money(subtotal.add(deliveryFee));

        Order order = new Order();
        order.setCustomerId(customerId);
        order.setStatus(OrderStatus.CREATED);
        order.setSubtotal(money(subtotal));
        order.setDeliveryFee(deliveryFee);
        order.setTotalAmount(totalAmount);
        order.setCurrency(pricing.getCurrency());
        order.setDeliveryAddress(request.deliveryAddress().trim());
        order.setCity(request.city().trim());
        order.setPostalCode(request.postalCode().trim());
        // Persisted before the lines so they have a parent to reference and so
        // order.created can carry the generated id.
        orderRepository.save(order);
        items.forEach(order::addItem);
        orderItemRepository.saveAll(items);

        List<OrderStatusHistory> timeline = List.of(
                appendHistory(order, OrderStatus.CREATED, null, StatusSource.CUSTOMER, "Order placed"));
        int itemCount = order.totalQuantity();

        eventPublisher.publish(KafkaTopics.ORDER_CREATED, String.valueOf(order.getId()),
                EventEnvelope.of(EventTypes.ORDER_CREATED, KafkaTopics.ORDER_CREATED, new OrderCreatedPayload(
                        order.getId(), customerId, order.getCity(), order.getPostalCode(), itemCount, totalAmount)));

        log.info("Created order {} for customer {} with {} units, total {} {} [correlationId={}]",
                order.getId(), customerId, itemCount, totalAmount, order.getCurrency(), CorrelationId.getOrCreate());

        return mapper.toResponse(order, items, timeline, itemCount);
    }

    private List<OrderLineRequest> requireDistinctLines(List<OrderLineRequest> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "An order must contain at least one line");
        }
        Set<Long> seen = new HashSet<>();
        for (OrderLineRequest line : lines) {
            if (line == null || line.productId() == null || line.quantity() == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Every line needs a productId and a quantity");
            }
            if (line.quantity() < 1) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Quantity for product " + line.productId() + " must be greater than zero");
            }
            if (!seen.add(line.productId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Product " + line.productId() + " appears on more than one line");
            }
        }
        return lines;
    }

    private Map<Long, ProductSnapshot> loadCatalogue(List<OrderLineRequest> lines) {
        Map<Long, ProductSnapshot> catalogue = productCatalogClient
                .findByIds(lines.stream().map(OrderLineRequest::productId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(ProductSnapshot::id, Function.identity()));
        for (OrderLineRequest line : lines) {
            if (!catalogue.containsKey(line.productId())) {
                throw ResourceNotFoundException.of("Product", line.productId());
            }
        }
        return catalogue;
    }

    // --------------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public OrderResponse getById(Long orderId) {
        Order order = findOrder(orderId);
        SecurityUtils.requireSelfOrStaff(order.getCustomerId());
        return describe(order);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> search(OrderStatus status, String search, LocalDate from, LocalDate to,
            int page, int size, String sort) {

        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "page must be zero or greater and size must be between 1 and " + MAX_PAGE_SIZE);
        }
        // Staff see the whole book of orders; anyone else is silently scoped to their
        // own rather than being told the filter is not theirs to set.
        Long customerId = SecurityUtils.isStaff() ? null : SecurityUtils.requireUserId();

        // A numeric search is an id lookup, anything else is matched against the
        // delivery text, which is what an operator typing in the search box expects.
        Long searchId = null;
        String textPattern = null;
        if (search != null && !search.isBlank()) {
            String trimmed = search.trim();
            searchId = asOrderId(trimmed);
            if (searchId == null) {
                textPattern = "%" + trimmed.toLowerCase(Locale.ROOT) + "%";
            }
        }

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Page<Order> found = orderRepository.search(customerId, status, searchId, textPattern,
                startOfDay(from, today.minusDays(DEFAULT_LOOKBACK_DAYS)),
                startOfNextDay(to, today),
                PageRequest.of(page, size, sortResolver.resolve(sort)));

        List<Long> orderIds = found.stream().map(Order::getId).toList();
        Map<Long, List<OrderItem>> itemsByOrder = groupItems(orderItemRepository.findByOrderIdInOrderByIdAsc(orderIds));
        Map<Long, List<OrderStatusHistory>> timelineByOrder =
                groupHistory(historyRepository.findByOrderIdInOrderByChangedAtAscIdAsc(orderIds));
        Map<Long, Integer> counts = itemCounts(orderIds);

        List<OrderResponse> content = found.stream()
                .map(order -> mapper.toResponse(order,
                        itemsByOrder.getOrDefault(order.getId(), List.of()),
                        timelineByOrder.getOrDefault(order.getId(), List.of()),
                        counts.getOrDefault(order.getId(), 0)))
                .toList();
        return PageResponse.from(found, content);
    }

    @Transactional(readOnly = true)
    public OrderSummaryResponse getSummary(Long orderId) {
        Order order = findOrder(orderId);
        return mapper.toSummary(order, itemCounts(List.of(orderId)).getOrDefault(orderId, 0));
    }

    @Transactional(readOnly = true)
    public List<OrderItemLineResponse> getItemLines(Long orderId) {
        // Loading the order first turns an unknown id into a 404 rather than an empty list.
        findOrder(orderId);
        return mapper.toItemLines(orderItemRepository.findByOrderIdOrderByIdAsc(orderId));
    }

    // ----------------------------------------------------------------- mutations

    @Transactional
    public OrderResponse cancel(Long orderId, CancelOrderRequest request) {
        Order order = findOrder(orderId);
        SecurityUtils.requireSelfOrStaff(order.getCustomerId());

        boolean staff = SecurityUtils.isStaff();
        OrderStatus from = order.getStatus();
        // The machine already refuses everything past PROCESSING; the extra check is the
        // customer rule, which stops them one state earlier than staff are allowed.
        if (!from.canTransitionTo(OrderStatus.CANCELLED) || (!staff && from == OrderStatus.PROCESSING)) {
            throw new InvalidStateTransitionException("Order", orderId, from.name(), OrderStatus.CANCELLED.name());
        }
        StatusSource source = staff ? StatusSource.OPERATIONS : StatusSource.CUSTOMER;
        String reason = truncate(reasonOrDefault(request, staff));

        order.setStatus(OrderStatus.CANCELLED);
        order.setCancelledReason(reason);
        // saveAndFlush so updatedAt is refreshed before the response is mapped.
        orderRepository.saveAndFlush(order);
        appendHistory(order, OrderStatus.CANCELLED, from, source, reason);

        log.info("Order {} cancelled by {} [correlationId={}]", orderId, source, CorrelationId.getOrCreate());
        return describe(order);
    }

    @Transactional
    public OrderResponse updateStatus(Long orderId, UpdateOrderStatusRequest request) {
        Order order = findOrder(orderId);
        OrderStatus from = order.getStatus();
        OrderStatus target = request.status();
        if (!from.canTransitionTo(target)) {
            throw new InvalidStateTransitionException("Order", orderId, from.name(), target.name());
        }

        order.setStatus(target);
        if (target == OrderStatus.CANCELLED) {
            order.setCancelledReason("Cancelled by operations");
        }
        orderRepository.saveAndFlush(order);
        appendHistory(order, target, from, StatusSource.OPERATIONS, truncate(request.note()));

        log.info("Order {} moved {} -> {} by operations [correlationId={}]", orderId, from, target,
                CorrelationId.getOrCreate());
        return describe(order);
    }

    @Transactional
    public OrderStatusHistory appendHistory(Order order, OrderStatus status, OrderStatus previousStatus,
            StatusSource source, String note) {

        OrderStatusHistory history = OrderStatusHistory.of(status, previousStatus, source, truncate(note),
                Instant.now());
        history.attachTo(order);
        return historyRepository.save(history);
    }

    // ------------------------------------------------------------------ read side

    @Transactional(readOnly = true)
    public Order findOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> ResourceNotFoundException.of("Order", orderId));
    }

    /** For the event path, where an unknown id is a stale message rather than a client error. */
    @Transactional(readOnly = true)
    public Optional<Order> findOptionalOrder(Long orderId) {
        return orderRepository.findById(orderId);
    }

    @Transactional(readOnly = true)
    public OrderResponse describe(Order order) {
        return mapper.toResponse(order,
                orderItemRepository.findByOrderIdOrderByIdAsc(order.getId()),
                historyRepository.findByOrderIdOrderByChangedAtAscIdAsc(order.getId()),
                countUnits(order.getId()));
    }

    // -------------------------------------------------------------------- helpers

    private Map<Long, Integer> itemCounts(Collection<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return orderItemRepository.sumQuantitiesByOrderIds(orderIds).stream()
                .collect(Collectors.toMap(
                        OrderItemRepository.ItemCountView::getOrderId,
                        view -> view.getItemCount().intValue()));
    }

    private int countUnits(Long orderId) {
        return itemCounts(List.of(orderId)).getOrDefault(orderId, 0);
    }

    private Map<Long, List<OrderItem>> groupItems(List<OrderItem> items) {
        Map<Long, List<OrderItem>> grouped = new HashMap<>();
        for (OrderItem item : items) {
            grouped.computeIfAbsent(item.getOrder().getId(), key -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    private Map<Long, List<OrderStatusHistory>> groupHistory(List<OrderStatusHistory> rows) {
        Map<Long, List<OrderStatusHistory>> grouped = new HashMap<>();
        for (OrderStatusHistory row : rows) {
            grouped.computeIfAbsent(row.getOrder().getId(), key -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private static Long asOrderId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Instant startOfDay(LocalDate date, LocalDate fallback) {
        return (date == null ? fallback : date).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant startOfNextDay(LocalDate date, LocalDate fallback) {
        return (date == null ? fallback : date).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_TEXT_LENGTH ? value : value.substring(0, MAX_TEXT_LENGTH);
    }

    private static String reasonOrDefault(CancelOrderRequest request, boolean staff) {
        if (request != null && request.reason() != null && !request.reason().isBlank()) {
            return request.reason().trim();
        }
        return staff ? "Cancelled by operations" : "Cancelled by customer";
    }
}
