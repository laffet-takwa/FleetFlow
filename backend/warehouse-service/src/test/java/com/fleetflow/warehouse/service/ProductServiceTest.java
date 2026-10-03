package com.fleetflow.warehouse.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.exception.ResourceNotFoundException;

import com.fleetflow.warehouse.config.ReservationProperties;
import com.fleetflow.warehouse.dto.CreateProductRequest;
import com.fleetflow.warehouse.dto.ProductResponse;
import com.fleetflow.warehouse.dto.UpdateProductRequest;
import com.fleetflow.warehouse.entity.Product;
import com.fleetflow.warehouse.mapper.ProductMapper;
import com.fleetflow.warehouse.repository.InventoryRepository;
import com.fleetflow.warehouse.repository.ProductRepository;

@DisplayName("ProductService")
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    private static final long GENERATED_ID = 99L;
    private static final long PREFERRED_WAREHOUSE = 1L;

    @Mock
    private ProductRepository productRepository;
    @Mock
    private InventoryRepository inventoryRepository;

    private ProductService productService;

    @BeforeEach
    void setUp() {
        ReservationProperties reservationProperties = new ReservationProperties();
        reservationProperties.setPreferredWarehouseId(PREFERRED_WAREHOUSE);
        productService = new ProductService(productRepository, inventoryRepository, reservationProperties,
                new ProductMapper());
    }

    @Test
    @DisplayName("rejects a duplicate SKU instead of silently creating a second product")
    void duplicateSkuIsRejected() {
        when(productRepository.existsBySkuIgnoreCase("FF-EL-0001")).thenReturn(true);

        CreateProductRequest request = new CreateProductRequest("FF-EL-0001", "Smart TV 55 pouces",
                null, ProductCategories.ELECTRONICS, new BigDecimal("1299.000"), true);

        BusinessException failure = assertThrows(BusinessException.class, () -> productService.create(request));

        assertEquals(ErrorCode.CONFLICT, failure.getErrorCode());
        assertTrue(failure.getMessage().contains("FF-EL-0001"));
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    @DisplayName("a new product defaults to active and reads as out of stock until it is stocked")
    void createDefaultsToActiveAndNoStock() {
        when(productRepository.existsBySkuIgnoreCase("ff-st-0020")).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            Product saved = invocation.getArgument(0);
            saved.setId(GENERATED_ID);
            return saved;
        });

        CreateProductRequest request = new CreateProductRequest(" ff-st-0020 ", "Ramette papier A4 x5",
                "80g", "stationery", new BigDecimal("39.900"), null);

        ProductResponse created = productService.create(request);

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(captor.capture());
        Product persisted = captor.getValue();

        assertEquals("ff-st-0020", persisted.getSku(), "the SKU is trimmed but not case folded");
        assertEquals(ProductCategories.STATIONERY, persisted.getCategory(), "the category is normalised");
        assertEquals(Boolean.TRUE, persisted.getActive(), "active defaults to true when omitted");
        assertEquals(GENERATED_ID, created.id());
        assertEquals("OUT_OF_STOCK", created.stockStatus());
        assertEquals(0, created.availableQuantity());
    }

    @Test
    @DisplayName("refuses a category outside the closed set rather than storing it")
    void unknownCategoryIsRejected() {
        when(productRepository.existsBySkuIgnoreCase("FF-XX-9999")).thenReturn(false);

        CreateProductRequest request = new CreateProductRequest("FF-XX-9999", "Article inconnu", null,
                "BAZAR", new BigDecimal("10.000"), true);

        BusinessException failure = assertThrows(BusinessException.class, () -> productService.create(request));

        assertEquals(ErrorCode.VALIDATION_FAILED, failure.getErrorCode());
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    @DisplayName("editing an unknown product is a 404")
    void updateUnknownProductIsNotFound() {
        when(productRepository.findById(404L)).thenReturn(java.util.Optional.empty());

        UpdateProductRequest request = new UpdateProductRequest("Renamed", null, null, null, Boolean.FALSE);

        ResourceNotFoundException failure =
                assertThrows(ResourceNotFoundException.class, () -> productService.update(404L, request));

        assertEquals(ErrorCode.NOT_FOUND, failure.getErrorCode());
    }

    @Test
    @DisplayName("a partial edit leaves the omitted fields alone")
    void updateAppliesOnlySuppliedFields() {
        Product product = new Product();
        product.setId(7L);
        product.setSku("FF-HO-0011");
        product.setName("Lampe de chevet LED doree");
        product.setCategory(ProductCategories.HOME);
        product.setPrice(new BigDecimal("45.750"));
        product.setActive(Boolean.TRUE);

        when(productRepository.findById(7L)).thenReturn(java.util.Optional.of(product));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryRepository.findByWarehouseIdAndProductIdIn(any(), anyList())).thenReturn(List.of());

        productService.update(7L, new UpdateProductRequest(null, null, null, new BigDecimal("49.900"), null));

        assertEquals("Lampe de chevet LED doree", product.getName());
        assertEquals(ProductCategories.HOME, product.getCategory());
        assertEquals(new BigDecimal("49.900"), product.getPrice());
        assertEquals(Boolean.TRUE, product.getActive());
    }

    @Test
    @DisplayName("rejects a nonsensical page request rather than clamping it silently")
    void rejectsBadPaging() {
        BusinessException failure =
                assertThrows(BusinessException.class, () -> productService.search(null, null, null, -1, 20));

        assertEquals(ErrorCode.BAD_REQUEST, failure.getErrorCode());
    }
}