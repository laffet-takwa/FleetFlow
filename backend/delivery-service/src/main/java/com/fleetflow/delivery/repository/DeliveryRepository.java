package com.fleetflow.delivery.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fleetflow.delivery.entity.Delivery;
import com.fleetflow.delivery.entity.DeliveryStatus;

public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    Optional<Delivery> findByOrderId(Long orderId);

    boolean existsByOrderId(Long orderId);

    boolean existsByDriverIdAndStatusIn(Long driverId, Collection<DeliveryStatus> statuses);

    boolean existsByVehicleIdAndStatusIn(Long vehicleId, Collection<DeliveryStatus> statuses);

    long countByStatus(DeliveryStatus status);

    long countByStatusIn(Collection<DeliveryStatus> statuses);

    long countByStatusAndCompletedAtGreaterThanEqual(DeliveryStatus status, Instant since);

    List<Delivery> findByStatusInOrderByCreatedAtDesc(Collection<DeliveryStatus> statuses);

    List<Delivery> findByDriverIdOrderByCreatedAtDesc(Long driverId, Pageable pageable);

    /** One batch query instead of a lookup per row when rendering a page of drivers. */
    @Query("""
            select d from Delivery d
            where d.driverId in :ids and d.status in :statuses
            order by d.createdAt desc
            """)
    List<Delivery> findOpenByDriverIds(@Param("ids") Collection<Long> ids,
            @Param("statuses") Collection<DeliveryStatus> statuses);

    @Query("""
            select d from Delivery d
            where d.vehicleId in :ids and d.status in :statuses
            order by d.createdAt desc
            """)
    List<Delivery> findOpenByVehicleIds(@Param("ids") Collection<Long> ids,
            @Param("statuses") Collection<DeliveryStatus> statuses);

    @Query("""
            select d.driverId as driverId, count(d) as total
            from Delivery d
            where d.driverId in :ids and d.status = :status
            group by d.driverId
            """)
    List<DriverDeliveryCount> countByDriverIdsAndStatus(@Param("ids") Collection<Long> ids,
            @Param("status") DeliveryStatus status);

    @Query("""
            select d from Delivery d
            where (:status is null or d.status = :status)
              and (:driverId is null or d.driverId = :driverId)
              and (:vehicleId is null or d.vehicleId = :vehicleId)
              and (:createdFrom is null or d.createdAt >= :createdFrom)
              and (:createdTo is null or d.createdAt < :createdTo)
            """)
    Page<Delivery> search(@Param("status") DeliveryStatus status,
            @Param("driverId") Long driverId,
            @Param("vehicleId") Long vehicleId,
            @Param("createdFrom") Instant createdFrom,
            @Param("createdTo") Instant createdTo,
            Pageable pageable);
}
