package com.fleetflow.auth.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fleetflow.auth.dto.CustomerProfilePreCreateRequest;
import com.fleetflow.auth.entity.User;
import com.fleetflow.common.correlation.CorrelationId;
import com.fleetflow.common.security.InternalTokenProperties;

/**
 * Hands a freshly registered account to customer-service.
 *
 * <p>The call is best effort: customer-service lazily materialises a skeleton profile
 * on first access, so a registration must never be rejected because the downstream
 * service happens to be down.
 */
@Component
public class CustomerProfileClient {

    private static final Logger log = LoggerFactory.getLogger(CustomerProfileClient.class);

    private static final String PROFILE_PATH = "/internal/api/customers";

    private final RestClient restClient;

    public CustomerProfileClient(RestClient.Builder restClientBuilder,
            @Value("${fleetflow.services.customer.base-url}") String baseUrl,
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

    public void preCreateProfile(User user, String accessToken) {
        CustomerProfilePreCreateRequest payload = new CustomerProfilePreCreateRequest(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getPhone(),
                user.getAddress());

        try {
            restClient.post()
                    .uri(PROFILE_PATH)
                    .header("Authorization", "Bearer " + accessToken)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Customer profile pre-created for user {} [correlationId={}]", user.getId(),
                    CorrelationId.getOrCreate());
        } catch (RestClientException ex) {
            log.warn("Customer profile pre-creation skipped for user {} [correlationId={}]: {}", user.getId(),
                    CorrelationId.getOrCreate(), ex.getMessage());
        }
    }
}