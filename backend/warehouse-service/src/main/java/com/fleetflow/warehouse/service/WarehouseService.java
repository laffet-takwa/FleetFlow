package com.fleetflow.warehouse.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fleetflow.common.correlation.CorrelationId;
import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.exception.ResourceNotFoundException;

import com.fleetflow.warehouse.dto.CreateWarehouseRequest;
import com.fleetflow.warehouse.dto.UpdateWarehouseRequest;
import com.fleetflow.warehouse.dto.WarehouseResponse;
import com.fleetflow.warehouse.entity.Warehouse;
import com.fleetflow.warehouse.entity.WarehouseStatus;
import com.fleetflow.warehouse.mapper.WarehouseMapper;
import com.fleetflow.warehouse.repository.WarehouseRepository;

@Service
public class WarehouseService {

    private static final Logger log = LoggerFactory.getLogger(WarehouseService.class);

    private final WarehouseRepository warehouseRepository;
    private final WarehouseMapper mapper;

    public WarehouseService(WarehouseRepository warehouseRepository, WarehouseMapper mapper) {
        this.warehouseRepository = warehouseRepository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<WarehouseResponse> list() {
        Map<Long, Long> stockedCounts = warehouseRepository.countStockedProductsByWarehouse().stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
        return warehouseRepository.findAll().stream()
                .map(warehouse -> mapper.toResponse(warehouse, stockedCounts.getOrDefault(warehouse.getId(), 0L)))
                .toList();
    }

    /** The reservation path needs the entity itself, not a view of it. */
    @Transactional(readOnly = true)
    public Warehouse getById(Long warehouseId) {
        return warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> ResourceNotFoundException.of("Warehouse", warehouseId));
    }

    @Transactional
    public WarehouseResponse create(CreateWarehouseRequest request) {
        String name = request.name().trim();
        if (warehouseRepository.existsByNameIgnoreCase(name)) {
            throw new BusinessException(ErrorCode.CONFLICT, "A warehouse named " + name + " already exists");
        }

        Warehouse warehouse = new Warehouse();
        warehouse.setName(name);
        warehouse.setAddress(request.address().trim());
        warehouse.setCity(request.city().trim());
        warehouse.setCapacity(request.capacity());
        // A new site opens for business; closure is an explicit edit, never an omission.
        warehouse.setStatus(WarehouseStatus.ACTIVE);

        warehouseRepository.save(warehouse);
        log.info("Created warehouse {} [correlationId={}]", warehouse.getName(), CorrelationId.getOrCreate());
        return mapper.toResponse(warehouse, 0L);
    }

    @Transactional
    public WarehouseResponse update(Long warehouseId, UpdateWarehouseRequest request) {
        Warehouse warehouse = getById(warehouseId);
        if (request.name() != null) {
            String name = request.name().trim();
            if (!name.equalsIgnoreCase(warehouse.getName()) && warehouseRepository.existsByNameIgnoreCase(name)) {
                throw new BusinessException(ErrorCode.CONFLICT, "A warehouse named " + name + " already exists");
            }
            warehouse.setName(name);
        }
        if (request.address() != null) {
            warehouse.setAddress(request.address().trim());
        }
        if (request.city() != null) {
            warehouse.setCity(request.city().trim());
        }
        if (request.capacity() != null) {
            warehouse.setCapacity(request.capacity());
        }
        if (request.status() != null) {
            warehouse.setStatus(request.status());
        }

        // saveAndFlush so the @PreUpdate timestamp is refreshed before it is mapped.
        warehouseRepository.saveAndFlush(warehouse);
        log.info("Updated warehouse {} [correlationId={}]", warehouse.getName(), CorrelationId.getOrCreate());
        return mapper.toResponse(warehouse, countStockedProducts(warehouse.getId()));
    }

    private long countStockedProducts(Long warehouseId) {
        return warehouseRepository.countStockedProducts(warehouseId);
    }
}