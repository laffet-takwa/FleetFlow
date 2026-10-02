package com.fleetflow.delivery.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fleetflow.delivery.entity.Vehicle;
import com.fleetflow.delivery.entity.VehicleStatus;
import com.fleetflow.delivery.entity.VehicleType;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    Optional<Vehicle> findByRegistrationNumber(String registrationNumber);

    boolean existsByRegistrationNumber(String registrationNumber);

    long countByStatus(VehicleStatus status);

    @Query("""
            select v from Vehicle v
            where (:status is null or v.status = :status)
              and (:type is null or v.type = :type)
              and (:search is null
                   or lower(v.registrationNumber) like lower(concat('%', :search, '%'))
                   or lower(v.type) like lower(concat('%', :search, '%')))
            """)
    Page<Vehicle> search(@Param("status") VehicleStatus status, @Param("type") VehicleType type,
            @Param("search") String search, Pageable pageable);
}
