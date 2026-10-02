package com.fleetflow.warehouse.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fleetflow.warehouse.entity.Inventory;

public interface InventoryRepository extends JpaRepository<Inventory, Long>, JpaSpecificationExecutor<Inventory> {

    Optional<Inventory> findByWarehouseIdAndProductId(Long warehouseId, Long productId);

    List<Inventory> findByWarehouseIdAndProductIdIn(Long warehouseId, Collection<Long> productIds);

    List<Inventory> findByProductIdInOrderByAvailableQuantityAscIdAsc(Collection<Long> productIds);

    /**
     * Low and out of stock together, ordered so the emptiest rows come first: an
     * operator scanning this list wants the zero rows before the nearly-empty ones.
     *
     * @param lowStockThreshold exclusive upper bound of the LOW_STOCK band, supplied by
     *        StockStatusResolver so the rule is not restated in SQL
     */
    @Query("""
            select i from Inventory i
            where i.availableQuantity < :lowStockThresholdExclusive
            order by i.availableQuantity asc, i.id asc
            """)
    List<Inventory> findNeedingAttention(@Param("lowStockThresholdExclusive") int lowStockThresholdExclusive);

    @Query("select count(i) from Inventory i where i.availableQuantity = 0")
    long countOutOfStock();

    @Query("select count(i) from Inventory i where i.availableQuantity > 0 and i.availableQuantity <= :threshold")
    long countLowStock(@Param("threshold") int threshold);

    @Query("select count(i) from Inventory i where i.availableQuantity > :threshold")
    long countInStock(@Param("threshold") int threshold);

    @Query("select count(distinct i.product.id) from Inventory i")
    long countDistinctProducts();
}