package com.fleetflow.order.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fleetflow.order.entity.OrderStatusHistory;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, Long> {

    /** The id tie-breaker keeps the timeline deterministic when two rows share a timestamp. */
    List<OrderStatusHistory> findByOrderIdOrderByChangedAtAscIdAsc(Long orderId);

    List<OrderStatusHistory> findByOrderIdInOrderByChangedAtAscIdAsc(Collection<Long> orderIds);
}
