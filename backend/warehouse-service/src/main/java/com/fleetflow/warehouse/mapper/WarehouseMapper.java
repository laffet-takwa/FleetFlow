package com.fleetflow.warehouse.mapper;

import org.springframework.stereotype.Component;

import com.fleetflow.warehouse.dto.WarehouseResponse;
import com.fleetflow.warehouse.entity.Warehouse;

@Component
public class WarehouseMapper {

    public WarehouseResponse toResponse(Warehouse warehouse, long distinctProductCount) {
        return new WarehouseResponse(
                warehouse.getId(),
                warehouse.getName(),
                warehouse.getAddress(),
                warehouse.getCity(),
                warehouse.getCapacity(),
                warehouse.getStatus().name(),
                distinctProductCount);
    }
}