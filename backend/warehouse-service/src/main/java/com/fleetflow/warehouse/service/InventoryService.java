package com.fleetflow.warehouse.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fleetflow.common.api.PageResponse;
import com.fleetflow.common.correlation.CorrelationId;
import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.exception.ResourceNotFoundException;

import com.fleetflow.warehouse.dto.AdjustInventoryRequest;
import com.fleetflow.warehouse.dto.InventoryResponse;
import com.fleetflow.warehouse.dto.LowStockResponse;
import com.fleetflow.warehouse.dto.StockSummary;
import com.fleetflow.warehouse.entity.Inventory;
import com.fleetflow.warehouse.entity.StockStatus;
import com.fleetflow.warehouse.mapper.InventoryMapper;
import com.fleetflow.warehouse.repository.InventoryRepository;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private static final int MAX_PAGE_SIZE = 200;
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "id");

    private final InventoryRepository inventoryRepository;
    private final InventoryMapper mapper;

    public InventoryService(InventoryRepository inventoryRepository, InventoryMapper mapper) {
        this.inventoryRepository = inventoryRepository;
        this.mapper = mapper;
    }

    /**
     * The stock status filter is part of the {@link Specification}, not a pass over the
     * page afterwards: filtering after paging would return short pages and report a
     * {@code totalElements} that no longer matches what the client is filtering on.
     */
    @Transactional(readOnly = true)
    public PageResponse<InventoryResponse> search(Long warehouseId, String search, StockStatus stockStatus,
            String category, int page, int size) {

        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "page must be zero or greater and size must be between 1 and " + MAX_PAGE_SIZE);
        }

        String pattern = search == null || search.isBlank() ? null
                : "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
        String wantedCategory = category == null || category.isBlank() ? null
                : category.trim().toUpperCase(Locale.ROOT);

        Specification<Inventory> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (warehouseId != null) {
                predicates.add(cb.equal(root.get("warehouse").get("id"), warehouseId));
            }
            if (stockStatus != null) {
                predicates.add(quantityInBand(root, cb, stockStatus));
            }
            if (pattern != null || wantedCategory != null) {
                Join<Inventory, ?> product = root.join("product", JoinType.INNER);
                if (pattern != null) {
                    predicates.add(cb.or(
                            cb.like(cb.lower(product.get("sku")), pattern),
                            cb.like(cb.lower(product.get("name")), pattern)));
                }
                if (wantedCategory != null) {
                    predicates.add(cb.equal(cb.upper(product.get("category")), wantedCategory));
                }
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<Inventory> found = inventoryRepository.findAll(specification,
                PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), DEFAULT_SORT));
        return PageResponse.from(found, found.stream().map(mapper::toResponse).toList());
    }

    @Transactional
    public InventoryResponse adjust(Long inventoryId, AdjustInventoryRequest request) {
        Inventory inventory = inventoryRepository.findById(inventoryId)
                .orElseThrow(() -> ResourceNotFoundException.of("Inventory", inventoryId));

        int delta = request.quantityDelta();
        int resulting = inventory.getAvailableQuantity() + delta;
        // Checked before the write so an over-correction is a clean 409 rather than a
        // CHECK constraint violation surfacing as a 500.
        if (resulting < 0) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "An adjustment of " + delta + " would leave " + resulting
                            + " units available for product " + inventory.getProduct().getSku());
        }

        inventory.setAvailableQuantity(resulting);
        inventoryRepository.saveAndFlush(inventory);

        log.info("Adjusted inventory {} by {} to {} units ({}) [correlationId={}]", inventoryId, delta, resulting,
                request.reason() == null || request.reason().isBlank() ? "no reason given" : request.reason(),
                CorrelationId.getOrCreate());
        return mapper.toResponse(inventory);
    }

    @Transactional(readOnly = true)
    public List<LowStockResponse> lowStock() {
        return inventoryRepository
                .findNeedingAttention(StockStatusResolver.bounds(StockStatus.LOW_STOCK).toExclusive())
                .stream()
                .map(mapper::toLowStockResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public StockSummary summary() {
        int threshold = StockStatusResolver.LOW_STOCK_THRESHOLD;
        return new StockSummary(
                inventoryRepository.countInStock(threshold),
                inventoryRepository.countLowStock(threshold),
                inventoryRepository.countOutOfStock(),
                inventoryRepository.countDistinctProducts());
    }

    /** The bands come from the resolver so the filter and the badge cannot drift apart. */
    private static Predicate quantityInBand(Root<Inventory> root, jakarta.persistence.criteria.CriteriaBuilder cb,
            StockStatus status) {

        StockStatusResolver.Range range = StockStatusResolver.bounds(status);
        Path<Integer> available = root.get("availableQuantity");
        if (range.isUnboundedAbove()) {
            return cb.greaterThanOrEqualTo(available, range.fromInclusive());
        }
        return cb.between(available, range.fromInclusive(), range.toInclusive());
    }
}