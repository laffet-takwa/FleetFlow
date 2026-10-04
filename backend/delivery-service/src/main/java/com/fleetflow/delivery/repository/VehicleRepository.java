package com.fleetflow.delivery.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fleetflow.delivery.entity.Vehicle;
import com.fleetflow.delivery.entity.VehicleStatus;
import com.fleetflow.delivery.entity.VehicleType;

import jakarta.persistence.LockModeType;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    /**
     * Locking read, for the same reason as {@code DriverRepository.findLockedById}: a
     * vehicle may not be attached to two open deliveries, and that has to be decided from
     * a row nobody else can change until the decision is written. Requires a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Vehicle v where v.id = :id")
    Optional<Vehicle> findLockedById(@Param("id") Long id);

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
