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

    /**
     * The search term is compared through {@code coalesce(:search, '') = ''} rather than
     * {@code :search is null}, for the same reason as {@link DriverRepository}: a nullable
     * String used only inside {@code concat} is bound as {@code bytea} and Postgres
     * rejects the whole query.
     *
     * <p>{@code type} is matched exactly rather than with {@code lower(v.type)}: it is
     * already an enum matched by the dedicated filter, and lowering an enum column is not
     * something the JPQL translator renders portably.
     */
    @Query("""
            select v from Vehicle v
            where (:status is null or v.status = :status)
              and (:type is null or v.type = :type)
              and (coalesce(:search, '') = ''
                   or lower(v.registrationNumber) like lower(concat('%', :search, '%')))
            """)
    Page<Vehicle> search(@Param("status") VehicleStatus status, @Param("type") VehicleType type,
            @Param("search") String search, Pageable pageable);
}
