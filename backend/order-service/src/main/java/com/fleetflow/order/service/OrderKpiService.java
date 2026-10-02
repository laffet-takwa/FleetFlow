package com.fleetflow.order.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fleetflow.order.dto.OrderKpiResponse;
import com.fleetflow.order.entity.OrderStatus;
import com.fleetflow.order.repository.OrderRepository;

@Service
public class OrderKpiService {

    /** Everything that is neither finished nor abandoned. */
    private static final List<OrderStatus> ACTIVE = List.of(
            OrderStatus.CREATED, OrderStatus.CONFIRMED, OrderStatus.PROCESSING,
            OrderStatus.READY_FOR_DELIVERY, OrderStatus.OUT_FOR_DELIVERY);

    /** Awaiting a warehouse or an operations decision, i.e. nothing is moving yet. */
    private static final List<OrderStatus> PENDING = List.of(OrderStatus.CREATED, OrderStatus.CONFIRMED);

    private final OrderRepository orderRepository;

    public OrderKpiService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /** Derived from one grouped count so the dashboard tiles can never disagree. */
    @Transactional(readOnly = true)
    public OrderKpiResponse kpis() {
        Map<OrderStatus, Long> byStatus = new EnumMap<>(OrderStatus.class);
        long total = 0L;
        for (Object[] row : orderRepository.countGroupedByStatus()) {
            OrderStatus status = (OrderStatus) row[0];
            long count = ((Number) row[1]).longValue();
            byStatus.put(status, count);
            total += count;
        }
        return new OrderKpiResponse(
                total,
                sum(byStatus, ACTIVE),
                byStatus.getOrDefault(OrderStatus.DELIVERED, 0L),
                sum(byStatus, PENDING),
                byStatus.getOrDefault(OrderStatus.CANCELLED, 0L));
    }

    private static long sum(Map<OrderStatus, Long> byStatus, List<OrderStatus> statuses) {
        return statuses.stream().mapToLong(status -> byStatus.getOrDefault(status, 0L)).sum();
    }
}
