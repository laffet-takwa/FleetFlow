package com.fleetflow.tracking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.exception.ResourceNotFoundException;
import com.fleetflow.tracking.config.FleetFlowTrackingProperties;
import com.fleetflow.tracking.document.DeliveryStatus;
import com.fleetflow.tracking.document.DeliveryTrackingState;
import com.fleetflow.tracking.document.LocationHistory;
import com.fleetflow.tracking.dto.LocationResponse;
import com.fleetflow.tracking.dto.LocationUpdateRequest;
import com.fleetflow.tracking.redis.LatestLocationStore;
import com.fleetflow.tracking.sse.SseDeliveryRegistry;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LocationIngestionService")
class LocationIngestionServiceTest {

    private static final long DELIVERY_ID = 3L;
    /** Delivery-service driver key: it names a row, not a person, so it must never authorise. */
    private static final long DRIVER_KEY = 1L;
    /** The auth-service user id the assigned driver presents as their JWT subject. */
    private static final long DRIVER_USER_ID = 3L;
    private static final long CUSTOMER_ID = 9L;
    private static final Instant NOW = Instant.parse("2026-10-02T14:00:00Z");
    private static final Instant PREVIOUS_FIX = Instant.parse("2026-10-02T13:58:00Z");
    private static final Instant NEXT_FIX = Instant.parse("2026-10-02T14:00:00Z");

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private LatestLocationStore latestLocationStore;

    @Mock
    private SseDeliveryRegistry sseDeliveryRegistry;

    private LocationIngestionService service;

    @BeforeEach
    void setUp() {
        service = new LocationIngestionService(mongoTemplate, latestLocationStore, sseDeliveryRegistry,
                new FleetFlowTrackingProperties(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("stores the point, refreshes Redis and broadcasts it")
    void storesAndPublishesAValidPoint() {
        givenActiveDelivery();

        LocationResponse stored = service.ingest(request(36.8065, 10.1815, NEXT_FIX), DRIVER_USER_ID, false);

        assertThat(stored.deliveryId()).isEqualTo(DELIVERY_ID);
        assertThat(stored.driverId()).isEqualTo(DRIVER_KEY);
        assertThat(stored.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(stored.latitude()).isEqualTo(36.8065);
        assertThat(stored.longitude()).isEqualTo(10.1815);
        assertThat(stored.recordedAt()).isEqualTo(NEXT_FIX);
        assertThat(stored.receivedAt()).isEqualTo(NOW);

        ArgumentCaptor<LocationHistory> history = ArgumentCaptor.forClass(LocationHistory.class);
        verify(mongoTemplate).save(history.capture());
        assertThat(history.getValue().getDeliveryId()).isEqualTo(DELIVERY_ID);
        assertThat(history.getValue().getDriverId()).isEqualTo(DRIVER_KEY);
        assertThat(history.getValue().getRecordedAt()).isEqualTo(NEXT_FIX);
        assertThat(history.getValue().getReceivedAt()).isEqualTo(NOW);

        verify(latestLocationStore).put(stored);
        verify(sseDeliveryRegistry).broadcast(DELIVERY_ID, stored);
    }

    @Test
    @DisplayName("advances the point count and the last fix on the state")
    void advancesTheStateCounters() {
        DeliveryTrackingState state = givenActiveDelivery();

        service.ingest(request(36.8065, 10.1815, NEXT_FIX), DRIVER_USER_ID, false);

        assertThat(state.getLocationCount()).isEqualTo(26);
        assertThat(state.getLastLocationAt()).isEqualTo(NEXT_FIX);
    }

    @Test
    @DisplayName("rejects a latitude beyond the configured maximum")
    void rejectsAnImpossibleLatitude() {
        givenActiveDelivery();

        BusinessException rejection = rejectionFor(request(91.0, 10.1815, NEXT_FIX), DRIVER_USER_ID, false);

        assertThat(rejection.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(mongoTemplate, never()).save(any(LocationHistory.class));
        verify(sseDeliveryRegistry, never()).broadcast(anyLong(), any());
    }

    @Test
    @DisplayName("rejects a longitude beyond the configured maximum")
    void rejectsAnImpossibleLongitude() {
        givenActiveDelivery();

        BusinessException rejection = rejectionFor(request(36.8065, 181.0, NEXT_FIX), DRIVER_USER_ID, false);

        assertThat(rejection.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("reports an unknown delivery as not found")
    void rejectsAnUnknownDelivery() {
        when(mongoTemplate.findById(DELIVERY_ID, DeliveryTrackingState.class)).thenReturn(null);

        Throwable thrown = catchThrowable(
                () -> service.ingest(request(36.8065, 10.1815, NEXT_FIX), DRIVER_USER_ID, false));

        assertThat(thrown).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Tracked delivery 3 was not found");
    }

    @Test
    @DisplayName("refuses to track a delivery whose tracking was switched off")
    void rejectsAStoppedDelivery() {
        DeliveryTrackingState state = givenActiveDelivery();
        state.setTrackingEnabled(false);

        BusinessException rejection = rejectionFor(request(36.8065, 10.1815, NEXT_FIX), DRIVER_USER_ID, false);

        assertThat(rejection.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(rejection).hasMessage("Tracking is only available for an active delivery");
        verify(latestLocationStore, never()).put(any());
    }

    @Test
    @DisplayName("refuses to track a delivered van even if the flag was left on")
    void rejectsATerminalStatus() {
        DeliveryTrackingState state = givenActiveDelivery();
        state.setStatus(DeliveryStatus.DELIVERED);

        BusinessException rejection = rejectionFor(request(36.8065, 10.1815, NEXT_FIX), DRIVER_USER_ID, false);

        assertThat(rejection.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("rejects a point that is not newer than the previous one")
    void rejectsAnOutOfOrderPoint() {
        givenActiveDelivery();

        BusinessException rejection = rejectionFor(request(36.8065, 10.1815, PREVIOUS_FIX), DRIVER_USER_ID, false);

        assertThat(rejection.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(rejection).hasMessage("Location update is out of order");
        verify(mongoTemplate, never()).save(any(LocationHistory.class));
    }

    @Test
    @DisplayName("rejects a driver posting for somebody else's delivery")
    void rejectsAnotherDriversPoint() {
        givenActiveDelivery();

        BusinessException rejection = rejectionFor(request(36.8065, 10.1815, NEXT_FIX), DRIVER_USER_ID + 1, false);

        assertThat(rejection.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verify(latestLocationStore, never()).put(any());
    }

    @Test
    @DisplayName("does not accept the delivery-service driver key as an identity")
    void rejectsTheDeliveryServiceDriverKey() {
        givenActiveDelivery();

        BusinessException rejection = rejectionFor(request(36.8065, 10.1815, NEXT_FIX), DRIVER_KEY, false);

        assertThat(rejection.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verify(latestLocationStore, never()).put(any());
    }

    @Test
    @DisplayName("refuses every driver while the delivery has no driverUserId yet")
    void rejectsAnyDriverForAnUnassignedDelivery() {
        DeliveryTrackingState state = givenActiveDelivery();
        state.setDriverUserId(null);

        BusinessException rejection = rejectionFor(request(36.8065, 10.1815, NEXT_FIX), DRIVER_USER_ID, false);

        assertThat(rejection.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verify(mongoTemplate, never()).save(any(LocationHistory.class));
        verify(latestLocationStore, never()).put(any());
    }

    @Test
    @DisplayName("still lets operations post for a delivery nobody has been assigned to")
    void allowsStaffToPostForAnUnassignedDelivery() {
        DeliveryTrackingState state = givenActiveDelivery();
        state.setDriverUserId(null);

        LocationResponse stored = service.ingest(request(36.8065, 10.1815, NEXT_FIX), 2L, true);

        assertThat(stored.deliveryId()).isEqualTo(DELIVERY_ID);
        verify(sseDeliveryRegistry).broadcast(DELIVERY_ID, stored);
    }

    @Test
    @DisplayName("lets operations post for any delivery")
    void allowsStaffToPostAnywhere() {
        givenActiveDelivery();

        LocationResponse stored = service.ingest(request(36.8065, 10.1815, NEXT_FIX), 2L, true);

        assertThat(stored.deliveryId()).isEqualTo(DELIVERY_ID);
        verify(sseDeliveryRegistry).broadcast(DELIVERY_ID, stored);
    }

    private DeliveryTrackingState givenActiveDelivery() {
        DeliveryTrackingState state = new DeliveryTrackingState();
        state.setDeliveryId(DELIVERY_ID);
        state.setOrderId(3L);
        state.setCustomerId(CUSTOMER_ID);
        state.setDriverId(DRIVER_KEY);
        state.setDriverUserId(DRIVER_USER_ID);
        state.setDriverName("Yassine Ben Salah");
        state.setStatus(DeliveryStatus.IN_TRANSIT);
        state.setTrackingEnabled(true);
        state.setLastLocationAt(PREVIOUS_FIX);
        state.setLocationCount(25);
        when(mongoTemplate.findById(DELIVERY_ID, DeliveryTrackingState.class)).thenReturn(state);
        return state;
    }

    private BusinessException rejectionFor(LocationUpdateRequest request, Long userId, boolean staff) {
        Throwable thrown = catchThrowable(() -> service.ingest(request, userId, staff));
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return (BusinessException) thrown;
    }

    private LocationUpdateRequest request(double latitude, double longitude, Instant recordedAt) {
        return new LocationUpdateRequest(DELIVERY_ID, latitude, longitude, 42.5, 275.0, recordedAt);
    }
}