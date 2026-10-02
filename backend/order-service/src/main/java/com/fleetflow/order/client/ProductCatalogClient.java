package com.fleetflow.order.client;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fleetflow.common.correlation.CorrelationId;
import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.security.InternalTokenProperties;

/**
 * Reads product name and price from the warehouse catalogue.
 *
 * <p>This is a synchronous lookup by necessity: an order cannot be priced from an
 * event, so checkout blocks on this call instead of trusting a client supplied price.
 */
@Component
public class ProductCatalogClient {

    private static final String PRODUCTS_PATH = "/internal/api/products";

    private final RestClient restClient;

    public ProductCatalogClient(RestClient.Builder restClientBuilder,
            @Value("${fleetflow.services.warehouse.base-url}") String baseUrl,
            InternalTokenProperties internalTokenProperties) {

        this.restClient = restClientBuilder.clone()
                .baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().set(InternalTokenProperties.HEADER, internalTokenProperties.getToken());
                    request.getHeaders().set(CorrelationId.HEADER, CorrelationId.getOrCreate());
                    return execution.execute(request, body);
                })
                .build();
    }

    /**
     * @return catalogue rows for the requested ids; ids the warehouse does not know are
     *         simply absent, so the caller decides how to report them
     * @throws BusinessException with {@link ErrorCode#SERVICE_UNAVAILABLE} when the
     *         warehouse cannot be reached or answers with an error
     */
    public List<ProductSnapshot> findByIds(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        String ids = productIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        try {
            List<ProductSnapshot> products = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path(PRODUCTS_PATH).queryParam("ids", ids).build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<ProductSnapshot>>() {
                    });
            return products == null ? List.of() : products;
        } catch (RestClientException ex) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "warehouse-service is unreachable");
        }
    }

    /** Catalogue row as published by the warehouse service. */
    public record ProductSnapshot(Long id, String sku, String name, BigDecimal price, Boolean active, String category) {
    }
}
