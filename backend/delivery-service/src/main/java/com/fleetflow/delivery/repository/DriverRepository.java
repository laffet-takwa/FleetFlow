package com.fleetflow.delivery.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fleetflow.delivery.entity.Driver;
import com.fleetflow.delivery.entity.DriverStatus;

import jakarta.persistence.LockModeType;

public interface DriverRepository extends JpaRepository<Driver, Long> {

    /**
     * Reads the driver row under a write lock. Every "is this driver free?" check is a
     * read followed by a write, so two concurrent assign requests for the same driver
     * would both see {@code AVAILABLE} and both hand it out. Serialising the read makes
     * the second one wait, then observe the {@code ON_DELIVERY} the first one wrote.
     * Requires an active transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Driver d where d.id = :id")
    Optional<Driver> findLockedById(@Param("id") Long id);

    /** Locking counterpart of {@link #findByUserId}, for a driver declaring their own status. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Driver d where d.userId = :userId")
    Optional<Driver> findLockedByUserId(@Param("userId") Long userId);

    Optional<Driver> findByUserId(Long userId);

    boolean existsByUserId(Long userId);

    long countByStatus(DriverStatus status);

    /**
     * The search term is compared through {@code coalesce(:search, '') = ''} rather than
     * {@code :search is null}. A nullable String parameter whose only other use is
     * {@code concat} gives Hibernate no JDBC type to infer, so it binds {@code bytea} and
     * Postgres rejects the query with "function lower(bytea) does not exist" — which
     * fails on the common no-search case, not just when a search term is supplied.
     */
    @Query("""
            select d from Driver d
            where (:status is null or d.status = :status)
              and (coalesce(:search, '') = ''
                   or lower(d.fullName) like lower(concat('%', :search, '%'))
                   or lower(d.licenseNumber) like lower(concat('%', :search, '%'))
                   or d.phone like concat('%', :search, '%'))
            """)
    Page<Driver> search(@Param("status") DriverStatus status, @Param("search") String search, Pageable pageable);
}
