package com.fleetflow.delivery.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fleetflow.common.correlation.CorrelationId;
import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.exception.ResourceNotFoundException;
import com.fleetflow.common.security.InternalTokenProperties;

import java.io.IOException;

/**
 * Synchronous read of the drop-off details. The driver needs a phone number before
 * a delivery starts, so this is a REST call rather than an event subscription.
 *
 * <p>The endpoint lives under {@code /internal/**} and is gated by the shared
 * internal token, which is why no user JWT is forwarded.
 */
@Component
public class CustomerServiceClient {

    private final RestClient restClient;

    public CustomerServiceClient(RestClient.Builder builder,
            @Value("${fleetflow.services.customer.base-url}") String baseUrl,
            @Value("${fleetflow.internal.token}") String internalToken,
            @Value("${fleetflow.services.customer.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${fleetflow.services.customer.read-timeout-ms:4000}") int readTimeoutMs) {

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);

        this.restClient = builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(new InternalHeadersInterceptor(internalToken))
                .build();
    }

    public CustomerContact getContactByUserId(Long userId) {
        try {
            CustomerContact contact = restClient.get()
                    .uri("/internal/api/customers/by-user/{userId}/contact", userId)
                    .retrieve()
                    .body(CustomerContact.class);
            if (contact == null) {
                throw ResourceNotFoundException.of("Customer", userId);
            }
            return contact;
        } catch (RestClientException ex) {
            // Surfaced as 503 so the Kafka consumer retries rather than opening a delivery
            // with no way to reach the customer.
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "customer-service is unreachable", ex);
        }
    }

    private record InternalHeadersInterceptor(String internalToken) implements ClientHttpRequestInterceptor {

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            request.getHeaders().set(InternalTokenProperties.HEADER, internalToken);
            request.getHeaders().set(CorrelationId.HEADER, CorrelationId.getOrCreate());
            return execution.execute(request, body);
        }
    }
}
