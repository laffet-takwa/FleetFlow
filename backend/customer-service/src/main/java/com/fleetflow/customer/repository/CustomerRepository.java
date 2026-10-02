package com.fleetflow.customer.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fleetflow.customer.entity.Customer;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByUserId(Long userId);

    /**
     * Free-text search across the fields an operations user would recognise a customer
     * by. An empty {@code pattern} matches every row, which lets the endpoint reuse one
     * query for both the unfiltered and the filtered listing.
     */
    @Query("""
            select c from Customer c
            where :pattern = ''
               or lower(c.email) like :pattern
               or lower(c.firstName) like :pattern
               or lower(c.lastName) like :pattern
               or lower(c.phone) like :pattern
               or lower(c.city) like :pattern
               or lower(c.postalCode) like :pattern
            """)
    Page<Customer> search(@Param("pattern") String pattern, Pageable pageable);
}
