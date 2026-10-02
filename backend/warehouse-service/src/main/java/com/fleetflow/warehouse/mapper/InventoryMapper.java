package com.fleetflow.warehouse.mapper;

import org.springframework.stereotype.Component;

import com.fleetflow.warehouse.dto.InventoryResponse;
import com.fleetflow.warehouse.dto.LowStockResponse;
import com.fleetflow.warehouse.entity.Inventory;
import com.fleetflow.warehouse.service.StockStatusResolver;

@Component
public class InventoryMapper {

    public InventoryResponse toResponse(Inventory inventory) {
        int available = inventory.getAvailableQuantity();
        return new InventoryResponse(
                inventory.getId(),
                inventory.getWarehouse().getId(),
                inventory.getWarehouse().getName(),
                inventory.getProduct().getId(),
                inventory.getProduct().getSku(),
                inventory.getProduct().getName(),
                inventory.getProduct().getCategory(),
                available,
                inventory.getReservedQuantity(),
                StockStatusResolver.resolve(available).name(),
                inventory.getUpdatedAt());
    }

    public LowStockResponse toLowStockResponse(Inventory inventory) {
        int available = inventory.getAvailableQuantity();
        return new LowStockResponse(
                inventory.getId(),
                inventory.getWarehouse().getId(),
                inventory.getWarehouse().getName(),
                inventory.getProduct().getId(),
                inventory.getProduct().getSku(),
                inventory.getProduct().getName(),
                inventory.getProduct().getCategory(),
                available,
                inventory.getReservedQuantity(),
                StockStatusResolver.resolve(available).name(),
                inventory.getUpdatedAt());
    }
}