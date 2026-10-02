package com.fleetflow.warehouse.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import com.fleetflow.warehouse.entity.Product;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    boolean existsBySkuIgnoreCase(String sku);

    Optional<Product> findBySkuIgnoreCase(String sku);

    List<Product> findByIdInOrderByIdAsc(List<Long> ids);

    /** Feeds the category filter dropdown, so only categories that are actually in use. */
    @Query("select distinct p.category from Product p order by p.category")
    List<String> findDistinctCategories();
}