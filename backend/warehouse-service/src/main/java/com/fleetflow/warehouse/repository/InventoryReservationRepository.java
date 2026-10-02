package com.fleetflow.warehouse.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fleetflow.warehouse.entity.InventoryReservation;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, Long> {

    /** The idempotency probe: a second {@code order.created} for the same order finds this true. */
    boolean existsByOrderId(Long orderId);

    List<InventoryReservation> findByOrderId(Long orderId);
}