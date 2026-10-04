package com.fleetflow.order.service;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fleetflow.common.correlation.CorrelationId;
import com.fleetflow.common.event.payload.DeliveryAssignedPayload;
import com.fleetflow.common.event.payload.DeliveryCompletedPayload;
import com.fleetflow.common.event.payload.DeliveryStatusChangedPayload;
import com.fleetflow.common.event.payload.InventoryInsufficientPayload;
import com.fleetflow.common.event.payload.InventoryReservedPayload;

import com.fleetflow.order.entity.Order;
import com.fleetflow.order.entity.OrderStatus;
import com.fleetflow.order.entity.StatusSource;

/**
 * Reacts to the upstream events that drive an order forward.
 *
 * <p>These handlers are deliberately tolerant. Kafka redelivers, and a downstream
 * service can legitimately emit a late or duplicated event, so a status machine that
 * threw on an unexpected starting point would poison the partition forever. A refused
 * transition is logged and recorded on the timeline instead of failing the record;
 * only the synchronous API paths raise {@link com.fleetflow.common.exception.InvalidStateTransitionException}.
 */
@Service
public class OrderLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(OrderLifecycleService.class);

    private static final int MAX_REASON_LENGTH = 255;

    private final OrderService orderService;

    public OrderLifecycleService(OrderService orderService) {
        this.orderService = orderService;
    }

    /** Stock is secured: the order is real and the customer can stop worrying. */
    @Transactional
    public void onInventoryReserved(InventoryReservedPayload payload) {
        withOrder(payload.orderId(), order ->
                transitionOrRecord(order, OrderStatus.CONFIRMED, "Inventory reserved"));
    }

    /** Stock could not be secured: the order dies here and the reason is what we show. */
    @Transactional
    public void onInventoryInsufficient(InventoryInsufficientPayload payload) {
        withOrder(payload.orderId(), order -> {
            String reason = describeShortfalls(payload.shortfalls());
            if (transitionOrRecord(order, OrderStatus.CANCELLED, reason)) {
                order.setCancelledReason(reason);
            }
        });
    }

    /**
     * The delivery service allocates the delivery on {@code inventory.reserved}, but it
     * is the human assignment of a driver and vehicle that promotes the order.
     */
    @Transactional
    public void onDeliveryAssigned(DeliveryAssignedPayload payload) {
        withOrder(payload.orderId(), order -> {
            if (order.getDeliveryId() == null) {
                order.setDeliveryId(payload.deliveryId());
            }
            String driver = payload.driverName() == null || payload.driverName().isBlank()
                    ? "a driver"
                    : payload.driverName();
            transitionOrRecord(order, OrderStatus.PROCESSING,
                    "Delivery " + payload.deliveryId() + " assigned to " + driver);
        });
    }

    /**
     * The van is rolling, which is two legal steps: the goods are ready and then they
     * are out. Collapsing them into one jump would put PROCESSING -> OUT_FOR_DELIVERY in
     * the machine, contradicting the published lifecycle.
     */
    @Transactional
    public void onDeliveryStarted(DeliveryStatusChangedPayload payload) {
        withOrder(payload.orderId(), order -> {
            transitionOrRecord(order, OrderStatus.READY_FOR_DELIVERY, "Goods ready for delivery");
            transitionOrRecord(order, OrderStatus.OUT_FOR_DELIVERY,
                    "Delivery " + payload.deliveryId() + " started");
        });
    }

    @Transactional
    public void onDeliveryCompleted(DeliveryCompletedPayload payload) {
        withOrder(payload.orderId(), order -> {
            if (order.getDeliveryId() == null) {
                order.setDeliveryId(payload.deliveryId());
            }
            String proof = payload.proofOfDelivery() == null || payload.proofOfDelivery().isBlank()
                    ? ""
                    : " (proof: " + payload.proofOfDelivery() + ")";
            transitionOrRecord(order, OrderStatus.DELIVERED, "Delivered" + proof);
        });
    }

    /**
     * A failed attempt deliberately leaves the order where it is: the goods are still
     * with the driver, and only a human should decide between a requeue and a cancel.
     */
    @Transactional
    public void onDeliveryFailed(DeliveryStatusChangedPayload payload) {
        withOrder(payload.orderId(), order -> {
            OrderStatus current = order.getStatus();
            orderService.appendHistory(order, current, current, StatusSource.SYSTEM,
                    "Delivery attempt failed: " + blankToUnknown(payload.reason()));
            log.warn("Delivery {} failed for order {} [correlationId={}]", payload.deliveryId(), order.getId(),
                    CorrelationId.getOrCreate());
        });
    }

    @Transactional
    public void onDeliveryCancelled(DeliveryStatusChangedPayload payload) {
        withOrder(payload.orderId(), order -> {
            if (order.getStatus() == OrderStatus.DELIVERED) {
                log.warn("Ignoring delivery.cancelled for delivered order {} [correlationId={}]", order.getId(),
                        CorrelationId.getOrCreate());
                return;
            }
            String reason = truncate("Delivery cancelled: " + blankToUnknown(payload.reason()));
            if (transitionOrRecord(order, OrderStatus.CANCELLED, reason)) {
                order.setCancelledReason(reason);
            }
        });
    }

    // -------------------------------------------------------------------- helpers

    private void withOrder(Long orderId, Consumer<Order> action) {
        Optional<Order> found = orderId == null ? Optional.empty() : orderService.findOptionalOrderForUpdate(orderId);
        if (found.isEmpty()) {
            log.warn("Ignoring an event for unknown order {} [correlationId={}]", orderId,
                    CorrelationId.getOrCreate());
            return;
        }
        action.accept(found.get());
    }

    /**
     * @return {@code true} when the order actually moved; {@code false} when the
     *         transition was illegal from the current status and was only recorded
     */
    private boolean transitionOrRecord(Order order, OrderStatus target, String note) {
        OrderStatus from = order.getStatus();
        if (!from.canTransitionTo(target)) {
            log.warn("Order {} cannot move {} -> {}; recorded as ignored [correlationId={}]", order.getId(), from,
                    target, CorrelationId.getOrCreate());
            orderService.appendHistory(order, from, from, StatusSource.SYSTEM,
                    "Ignored " + from + " -> " + target + ": " + note);
            return false;
        }
        order.setStatus(target);
        orderService.appendHistory(order, target, from, StatusSource.SYSTEM, note);
        log.info("Order {} moved {} -> {} by event [correlationId={}]", order.getId(), from, target,
                CorrelationId.getOrCreate());
        return true;
    }

    private static String describeShortfalls(List<InventoryInsufficientPayload.Shortfall> shortfalls) {
        if (shortfalls == null || shortfalls.isEmpty()) {
            return "Insufficient stock for this order";
        }
        String detail = shortfalls.stream()
                .map(shortfall -> shortfall.productName() + " (requested " + shortfall.requested()
                        + ", available " + shortfall.available() + ")")
                .collect(Collectors.joining(", "));
        return truncate("Insufficient stock: " + detail);
    }

    private static String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "no reason given" : value;
    }

    private static String truncate(String value) {
        return value.length() <= MAX_REASON_LENGTH ? value : value.substring(0, MAX_REASON_LENGTH);
    }
}
