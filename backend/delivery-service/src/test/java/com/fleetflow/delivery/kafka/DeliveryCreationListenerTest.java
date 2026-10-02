package com.fleetflow.delivery.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.fleetflow.common.correlation.CorrelationId;
import com.fleetflow.common.event.EventEnvelope;
import com.fleetflow.common.event.EventTypes;
import com.fleetflow.common.event.KafkaTopics;
import com.fleetflow.common.event.payload.InventoryReservedPayload;
import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.idempotency.IdempotencyService;
import com.fleetflow.delivery.client.CustomerContact;
import com.fleetflow.delivery.client.CustomerServiceClient;
import com.fleetflow.delivery.config.KafkaTopicProperties;
import com.fleetflow.delivery.config.WarehouseProperties;
import com.fleetflow.delivery.entity.Delivery;
import com.fleetflow.delivery.entity.DeliveryStatus;
import com.fleetflow.delivery.mapper.DeliveryMapper;
import com.fleetflow.delivery.repository.DeliveryRepository;
import com.fleetflow.delivery.repository.DriverRepository;
import com.fleetflow.delivery.repository.VehicleRepository;
import com.fleetflow.delivery.service.DeliveryService;

@ExtendWith(MockitoExtension.class)
@DisplayName("inventory.reserved opens one delivery per order")
class DeliveryCreationListenerTest {

    private static final String EVENT_ID = "0f0f7c1e-6b1f-4a52-9a3d-6b0b2c9d5e11";
    private static final String CORRELATION_ID = "c4f2a1c8-9d31-4f0a-9d2b-7c5a1b3e4d55";

    @Mock
    private DeliveryRepository deliveryRepository;
    @Mock
    private DriverRepository driverRepository;
    @Mock
    private VehicleRepository vehicleRepository;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private CustomerServiceClient customerServiceClient;

    @Captor
    private ArgumentCaptor<Delivery> deliveryCaptor;

    /** Correlation id observed from inside the handler, i.e. after the scope was opened. */
    private final AtomicReference<String> correlationIdSeenByHandler = new AtomicReference<>();

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private DeliveryService deliveryService;

    @BeforeEach
    void setUp() {
        CorrelationId.clear();
        deliveryService = new DeliveryService(deliveryRepository, driverRepository, vehicleRepository,
                new NoopPublisher(), new KafkaTopicProperties(), customerServiceClient,
                new WarehouseProperties(), new DeliveryMapper());
        // Pass the action straight through so the test exercises the real handler
        // instead of a stubbed one.
        when(idempotencyService.executeOnce(anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(2).run();
                    return true;
                });
    }

    @Test
    @DisplayName("creates one CREATED delivery and stores the customer snapshot")
    void createsDeliveryWithCustomerSnapshot() throws Exception {
        givenCustomerContact();
        when(deliveryRepository.existsByOrderId(42L)).thenReturn(false);

        new DeliveryCreationListener(objectMapper, idempotencyService, deliveryService)
                .onInventoryReserved(envelopeJson());

        verify(deliveryRepository, org.mockito.Mockito.times(1)).save(deliveryCaptor.capture());
        Delivery delivery = deliveryCaptor.getValue();

        assertThat(delivery.getOrderId()).isEqualTo(42L);
        assertThat(delivery.getCustomerId()).isEqualTo(8L);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.CREATED);
        assertThat(delivery.getDriverId()).isNull();
        assertThat(delivery.getVehicleId()).isNull();
        assertThat(delivery.getPickupAddress()).isEqualTo("Zone Industrielle El Mghira");
        assertThat(delivery.getDeliveryAddress()).isEqualTo("14 Rue de la Liberte");
        assertThat(delivery.getCity()).isEqualTo("Ariana");
        assertThat(delivery.getPostalCode()).isEqualTo("1010");
        assertThat(delivery.getCustomerName()).isEqualTo("Amel Trabelsi");
        assertThat(delivery.getCustomerPhone()).isEqualTo("+21620123456");
    }

    @Test
    @DisplayName("a repeated reservation for the same order is a no-op")
    void duplicateReservationIsANoOp() throws Exception {
        when(deliveryRepository.existsByOrderId(42L)).thenReturn(true);

        new DeliveryCreationListener(objectMapper, idempotencyService, deliveryService)
                .onInventoryReserved(envelopeJson());

        verify(deliveryRepository, never()).save(any());
        verify(customerServiceClient, never()).getContactByUserId(any());
    }

    @Test
    @DisplayName("the handler is wrapped in executeOnce and runs under the event correlation id")
    void isIdempotentAndKeepsTheCorrelationId() throws Exception {
        givenCustomerContact();
        when(deliveryRepository.existsByOrderId(42L)).thenReturn(false);

        new DeliveryCreationListener(objectMapper, idempotencyService, deliveryService)
                .onInventoryReserved(envelopeJson());

        verify(idempotencyService).executeOnce(eq(EVENT_ID), eq(EventTypes.INVENTORY_RESERVED), any());
        assertThat(correlationIdSeenByHandler.get()).isEqualTo(CORRELATION_ID);
        assertThat(CorrelationId.get()).as("the scope must not leak past the listener").isNull();
    }

    @Test
    @DisplayName("an unreachable customer-service propagates so Kafka retries")
    void propagatesServiceUnavailable() throws Exception {
        when(deliveryRepository.existsByOrderId(42L)).thenReturn(false);
        when(customerServiceClient.getContactByUserId(8L))
                .thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "customer-service is unreachable"));

        DeliveryCreationListener listener = new DeliveryCreationListener(objectMapper, idempotencyService,
                deliveryService);

        assertThatThrownBy(() -> listener.onInventoryReserved(envelopeJson()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));

        verify(deliveryRepository, never()).save(any());
    }

    private void givenCustomerContact() {
        when(customerServiceClient.getContactByUserId(8L)).thenAnswer(invocation -> {
            correlationIdSeenByHandler.set(CorrelationId.getOrCreate());
            return new CustomerContact(8L, "Amel", "Trabelsi", "customer1@fleetflow.local",
                    "+21620123456", "14 Rue de la Liberte", "Ariana", "1010");
        });
    }

    private String envelopeJson() throws Exception {
        EventEnvelope<InventoryReservedPayload> envelope = new EventEnvelope<>(
                EVENT_ID,
                EventTypes.INVENTORY_RESERVED,
                KafkaTopics.INVENTORY_RESERVED,
                CORRELATION_ID,
                java.time.Instant.parse("2026-10-02T13:53:11.204Z"),
                new InventoryReservedPayload(42L, 8L, 1L, "Tunis Main Warehouse",
                        new BigDecimal("128.500"), 3));
        return objectMapper.writeValueAsString(envelope);
    }

    /** Creation publishes nothing, so the publisher is not part of this test's contract. */
    private static final class NoopPublisher
            implements com.fleetflow.common.event.DomainEventPublisher {

        @Override
        public <T> void publish(String topic, String key, EventEnvelope<T> envelope) {
            throw new AssertionError("delivery creation must not publish: " + topic);
        }
    }
}
