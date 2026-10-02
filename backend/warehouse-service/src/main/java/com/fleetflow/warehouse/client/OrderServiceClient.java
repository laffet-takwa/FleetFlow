package com.fleetflow.warehouse.client;

import java.util.List;

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
 * Reads the ordered lines of an order from the order service.
 *
 * <p>{@code OrderCreatedPayload} deliberately carries no line detail, so reservation
 * has to fetch it. That call is on the critical path of a Kafka consumer: if it fails
 * the exception is rethrown rather than swallowed, because the alternative is an order
 * whose stock was never held, which is worse than a redelivered message.
 */
@Component
public class OrderServiceClient {

    private static final String ITEMS_PATH = "/internal/api/orders/{orderId}/items";

    private final RestClient restClient;

    public OrderServiceClient(RestClient.Builder restClientBuilder,
            @Value("${fleetflow.services.order.base-url}") String baseUrl,
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
     * @throws BusinessException with {@link ErrorCode#SERVICE_UNAVAILABLE} when the order
     *         service cannot be reached, so Kafka redelivers {@code order.created}
     */
    public List<OrderLine> findItemLines(Long orderId) {
        try {
            List<OrderLine> lines = restClient.get()
                    .uri(ITEMS_PATH, orderId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<OrderLine>>() {
                    });
            return lines == null ? List.of() : lines;
        } catch (RestClientException ex) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "order-service is unreachable", ex);
        }
    }

    /** One ordered line: product id and quantity, which is all reservation needs. */
    public record OrderLine(Long productId, Integer quantity) {
    }
}