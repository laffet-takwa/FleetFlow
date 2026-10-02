package com.fleetflow.warehouse.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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

import com.fleetflow.warehouse.config.ReservationProperties;
import com.fleetflow.warehouse.dto.CreateProductRequest;
import com.fleetflow.warehouse.dto.ProductResponse;
import com.fleetflow.warehouse.dto.ProductSnapshot;
import com.fleetflow.warehouse.dto.UpdateProductRequest;
import com.fleetflow.warehouse.entity.Inventory;
import com.fleetflow.warehouse.entity.Product;
import com.fleetflow.warehouse.mapper.ProductMapper;
import com.fleetflow.warehouse.repository.InventoryRepository;
import com.fleetflow.warehouse.repository.ProductRepository;

import jakarta.persistence.criteria.Predicate;

@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private static final int MAX_PAGE_SIZE = 200;
    /** Name ordering keeps paging stable; the generated id alone would shuffle ties. */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "name");

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final ReservationProperties reservationProperties;
    private final ProductMapper mapper;

    public ProductService(ProductRepository productRepository,
            InventoryRepository inventoryRepository,
            ReservationProperties reservationProperties,
            ProductMapper mapper) {

        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
        this.reservationProperties = reservationProperties;
        this.mapper = mapper;
    }

    // -------------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> search(String search, String category, Boolean active,
            int page, int size) {

        requirePaging(page, size);
        String pattern = search == null || search.isBlank() ? null
                : "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
        String wantedCategory = category == null || category.isBlank() ? null
                : category.trim().toUpperCase(Locale.ROOT);

        Specification<Product> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (pattern != null) {
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("sku")), pattern),
                        cb.like(cb.lower(root.get("name")), pattern)));
            }
            if (wantedCategory != null) {
                predicates.add(cb.equal(cb.upper(root.get("category")), wantedCategory));
            }
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<Product> found = productRepository.findAll(specification,
                PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), DEFAULT_SORT));
        return PageResponse.from(found, withStock(found.getContent()));
    }

    @Transactional(readOnly = true)
    public ProductResponse getById(Long productId) {
        return mapper.toResponse(findProduct(productId), stockFor(List.of(productId)));
    }

    @Transactional(readOnly = true)
    public List<String> listCategories() {
        return productRepository.findDistinctCategories();
    }

    // -------------------------------------------------------------- internal API

    /**
     * Ids the catalogue does not know are simply absent from the result, so the caller
     * decides how to report them rather than this service guessing at an error.
     */
    @Transactional(readOnly = true)
    public List<ProductSnapshot> snapshots(Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        return productRepository.findByIdInOrderByIdAsc(List.copyOf(productIds)).stream()
                .map(mapper::toSnapshot)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductSnapshot snapshot(Long productId) {
        return mapper.toSnapshot(findProduct(productId));
    }

    // ---------------------------------------------------------------- mutations

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        String sku = request.sku().trim();
        if (productRepository.existsBySkuIgnoreCase(sku)) {
            throw new BusinessException(ErrorCode.CONFLICT, "A product with SKU " + sku + " already exists");
        }

        Product product = new Product();
        product.setSku(sku);
        product.setName(request.name().trim());
        product.setDescription(trimToNull(request.description()));
        product.setCategory(ProductCategories.normalise(request.category()));
        product.setPrice(request.price());
        product.setActive(request.active() == null ? Boolean.TRUE : request.active());

        productRepository.save(product);
        log.info("Created product {} (id {}) [correlationId={}]", product.getSku(), product.getId(),
                CorrelationId.getOrCreate());
        return mapper.toResponse(product, Map.of());
    }

    @Transactional
    public ProductResponse update(Long productId, UpdateProductRequest request) {
        Product product = findProduct(productId);
        if (request.name() != null) {
            product.setName(request.name().trim());
        }
        if (request.description() != null) {
            product.setDescription(trimToNull(request.description()));
        }
        if (request.category() != null) {
            product.setCategory(ProductCategories.normalise(request.category()));
        }
        if (request.price() != null) {
            product.setPrice(request.price());
        }
        if (request.active() != null) {
            product.setActive(request.active());
        }

        // saveAndFlush so the @PreUpdate timestamp is refreshed before it is mapped.
        productRepository.saveAndFlush(product);
        log.info("Updated product {} [correlationId={}]", product.getSku(), CorrelationId.getOrCreate());
        return mapper.toResponse(product, stockFor(List.of(productId)));
    }

    // ------------------------------------------------------------------ helpers

    public Product findProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> ResourceNotFoundException.of("Product", productId));
    }

    /**
     * Stock is read for one warehouse in a single extra query for the whole page, not
     * once per row: this is the most called endpoint in the service and the listing is
     * rendered as a grid.
     */
    private List<ProductResponse> withStock(List<Product> products) {
        Map<Long, Inventory> stock = stockFor(products.stream().map(Product::getId).toList());
        return products.stream().map(product -> mapper.toResponse(product, stock)).toList();
    }

    private Map<Long, Inventory> stockFor(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return inventoryRepository
                .findByWarehouseIdAndProductIdIn(reservationProperties.getPreferredWarehouseId(), productIds)
                .stream()
                .collect(Collectors.toMap(
                        inventory -> inventory.getProduct().getId(),
                        Function.identity(),
                        (first, second) -> first));
    }

    private void requirePaging(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "page must be zero or greater and size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}