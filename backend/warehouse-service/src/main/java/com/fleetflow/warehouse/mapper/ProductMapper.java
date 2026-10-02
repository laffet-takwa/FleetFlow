package com.fleetflow.warehouse.mapper;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.fleetflow.warehouse.dto.ProductResponse;
import com.fleetflow.warehouse.dto.ProductSnapshot;
import com.fleetflow.warehouse.entity.Inventory;
import com.fleetflow.warehouse.entity.Product;
import com.fleetflow.warehouse.service.StockStatusResolver;

@Component
public class ProductMapper {

    /**
     * @param stockByProduct inventory of each product at the warehouse the caller cares
     *                       about; a product missing from the map is simply not stocked
     *                       there, which reads as zero rather than as unknown
     */
    public ProductResponse toResponse(Product product, Map<Long, Inventory> stockByProduct) {
        Inventory stock = stockByProduct.get(product.getId());
        int available = stock == null ? 0 : stock.getAvailableQuantity();
        int reserved = stock == null ? 0 : stock.getReservedQuantity();
        return new ProductResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.getCategory(),
                product.getPrice(),
                Boolean.TRUE.equals(product.getActive()),
                available,
                reserved,
                StockStatusResolver.resolve(available).name());
    }

    public ProductSnapshot toSnapshot(Product product) {
        return new ProductSnapshot(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getPrice(),
                Boolean.TRUE.equals(product.getActive()),
                product.getCategory());
    }
}