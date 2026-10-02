package com.fleetflow.delivery.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fleetflow.delivery.entity.Driver;
import com.fleetflow.delivery.entity.DriverStatus;

public interface DriverRepository extends JpaRepository<Driver, Long> {

    Optional<Driver> findByUserId(Long userId);

    boolean existsByUserId(Long userId);

    long countByStatus(DriverStatus status);

    @Query("""
            select d from Driver d
            where (:status is null or d.status = :status)
              and (:search is null
                   or lower(d.fullName) like lower(concat('%', :search, '%'))
                   or lower(d.licenseNumber) like lower(concat('%', :search, '%'))
                   or d.phone like concat('%', :search, '%'))
            """)
    Page<Driver> search(@Param("status") DriverStatus status, @Param("search") String search, Pageable pageable);
}
