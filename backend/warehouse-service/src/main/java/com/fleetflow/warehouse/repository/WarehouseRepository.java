package com.fleetflow.warehouse.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fleetflow.warehouse.entity.Warehouse;

public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

    boolean existsByNameIgnoreCase(String name);

    /**
     * One grouped query for the whole warehouse list. Counting per warehouse in a loop
     * would make the list N+1 for a screen that shows every site.
     *
     * @return pairs of {@code [warehouseId, distinctProductCount]}
     */
    @Query("select i.warehouse.id, count(i) from Inventory i group by i.warehouse.id")
    List<Object[]> countStockedProductsByWarehouse();

    @Query("select count(i) from Inventory i where i.warehouse.id = :warehouseId")
    long countStockedProducts(@Param("warehouseId") Long warehouseId);
}