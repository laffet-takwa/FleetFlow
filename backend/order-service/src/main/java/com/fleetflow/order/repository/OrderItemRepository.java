package com.fleetflow.order.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fleetflow.order.entity.OrderItem;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderIdOrderByIdAsc(Long orderId);

    List<OrderItem> findByOrderIdInOrderByIdAsc(Collection<Long> orderIds);

    /**
     * Summed quantities for a whole page of orders in one round trip: the list endpoint
     * needs an item count per order and must not fan out into a query per row.
     */
    @Query("""
            select i.order.id as orderId, sum(i.quantity) as itemCount
            from OrderItem i
            where i.order.id in :orderIds
            group by i.order.id
            """)
    List<ItemCountView> sumQuantitiesByOrderIds(@Param("orderIds") Collection<Long> orderIds);

    interface ItemCountView {

        Long getOrderId();

        Long getItemCount();
    }
}
