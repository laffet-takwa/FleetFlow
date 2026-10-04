package com.fleetflow.order.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fleetflow.order.entity.Order;
import com.fleetflow.order.entity.OrderStatus;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * Every status change is read-check-write on this row: load, ask the machine whether
     * the move is legal, write. Without the write lock two operators (or an operator and a
     * Kafka event) both see the same starting status, both pass the check and both append
     * a timeline row, leaving the history contradicting the stored status.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    /**
     * Backing query for the order list. The caller resolves {@code search} into either
     * an id or a text pattern, and clamps the date window, so every parameter here has
     * a concrete value and the only dynamic part left is the whitelisted sort.
     */
    @Query("""
            select o from Order o
            where (:customerId is null or o.customerId = :customerId)
              and (:status is null or o.status = :status)
              and (:searchId is null or o.id = :searchId)
              and (:textPattern is null
                   or lower(o.deliveryAddress) like :textPattern
                   or lower(o.city) like :textPattern)
              and o.createdAt >= :from
              and o.createdAt < :toExclusive
            """)
    Page<Order> search(@Param("customerId") Long customerId,
            @Param("status") OrderStatus status,
            @Param("searchId") Long searchId,
            @Param("textPattern") String textPattern,
            @Param("from") Instant from,
            @Param("toExclusive") Instant toExclusive,
            Pageable pageable);

    /** One grouped query backs the whole KPI block instead of five separate counts. */
    @Query("select o.status, count(o) from Order o group by o.status")
    List<Object[]> countGroupedByStatus();

    @Query(value = """
            select to_char(created_at at time zone 'UTC', 'YYYY-MM-DD') as day,
                   count(*) as orders,
                   count(*) filter (where status = 'DELIVERED') as delivered,
                   count(*) filter (where status = 'CANCELLED') as cancelled
            from orders
            where created_at >= :from and created_at < :toExclusive
            group by 1
            """, nativeQuery = true)
    List<Object[]> countDailyCreatedBetween(@Param("from") Instant from,
            @Param("toExclusive") Instant toExclusive);
}
