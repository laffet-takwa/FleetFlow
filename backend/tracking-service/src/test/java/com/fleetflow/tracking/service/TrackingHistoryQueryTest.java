package com.fleetflow.tracking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.security.FleetRole;
import com.fleetflow.common.security.JwtPrincipal;
import com.fleetflow.tracking.config.FleetFlowTrackingProperties;
import com.fleetflow.tracking.document.DeliveryStatus;
import com.fleetflow.tracking.document.DeliveryTrackingState;
import com.fleetflow.tracking.document.LocationHistory;
import com.fleetflow.tracking.dto.LocationResponse;
import com.fleetflow.tracking.dto.TrackingHistoryResponse;
import com.fleetflow.tracking.redis.LatestLocationStore;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TrackingQueryService history and authorisation")
class TrackingHistoryQueryTest {

    private static final long DELIVERY_ID = 3L;
    private static final long CUSTOMER_ID = 9L;
    /** Delivery-service driver key: it names a row, not a person, so it must never authorise. */
    private static final long DRIVER_KEY = 1L;
    /** The auth-service user id the assigned driver presents as their JWT subject. */
    private static final long DRIVER_USER_ID = 3L;
    private static final Instant NOW = Instant.parse("2026-10-02T14:00:00Z");

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private LatestLocationStore latestLocationStore;

    private TrackingQueryService service;

    @BeforeEach
    void setUp() {
        service = new TrackingQueryService(mongoTemplate, latestLocationStore,
                new FleetFlowTrackingProperties(), Clock.fixed(NOW, ZoneOffset.UTC));
        givenState();
        authenticateAs(FleetRole.OPERATIONS, 2L);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("returns the trail oldest first, ready to draw")
    void returnsTheTrailOldestFirst() {
        when(mongoTemplate.find(any(Query.class), eq(LocationHistory.class)))
                .thenReturn(List.of(history(1), history(2), history(3)));

        TrackingHistoryResponse response = service.history(DELIVERY_ID, 200);

        assertThat(response.deliveryId()).isEqualTo(DELIVERY_ID);
        assertThat(response.count()).isEqualTo(3);
        assertThat(response.locations()).extracting(LocationResponse::recordedAt).isSorted();
        assertThat(response.locations().get(0).latitude()).isEqualTo(36.8065);
    }

    @Test
    @DisplayName("asks Mongo for the newest points first and caps the batch")
    void appliesTheLimitAndAscendingSort() {
        when(mongoTemplate.find(any(Query.class), eq(LocationHistory.class))).thenReturn(List.of());

        service.history(DELIVERY_ID, 50);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(query.capture(), eq(LocationHistory.class));
        assertThat(query.getValue().getLimit()).isEqualTo(50);
        assertThat(query.getValue().getSortObject()).containsEntry("recordedAt", 1);
    }

    @Test
    @DisplayName("never returns more than the hard cap, whatever the caller asks for")
    void clampsAnAbsurdLimit() {
        when(mongoTemplate.find(any(Query.class), eq(LocationHistory.class))).thenReturn(List.of());

        service.history(DELIVERY_ID, 5_000_000);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(query.capture(), eq(LocationHistory.class));
        assertThat(query.getValue().getLimit()).isEqualTo(TrackingQueryService.MAX_HISTORY_LIMIT);
    }

    @Test
    @DisplayName("never returns an empty trail because of a nonsensical limit")
    void clampsAZeroLimit() {
        when(mongoTemplate.find(any(Query.class), eq(LocationHistory.class))).thenReturn(List.of());

        service.history(DELIVERY_ID, 0);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(query.capture(), eq(LocationHistory.class));
        assertThat(query.getValue().getLimit()).isEqualTo(1);
    }

    @Test
    @DisplayName("returns an empty trail for a delivery that never reported")
    void handlesAnEmptyTrail() {
        when(mongoTemplate.find(any(Query.class), eq(LocationHistory.class))).thenReturn(List.of());

        TrackingHistoryResponse response = service.history(DELIVERY_ID, 200);

        assertThat(response.count()).isZero();
        assertThat(response.locations()).isEmpty();
    }

    @Test
    @DisplayName("lets the owning customer read the trail")
    void allowsTheOwningCustomer() {
        authenticateAs(FleetRole.CUSTOMER, CUSTOMER_ID);
        when(mongoTemplate.find(any(Query.class), eq(LocationHistory.class))).thenReturn(List.of(history(1)));

        assertThat(service.history(DELIVERY_ID, 200).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("lets the assigned driver read the trail")
    void allowsTheAssignedDriver() {
        authenticateAs(FleetRole.DRIVER, DRIVER_USER_ID);
        when(mongoTemplate.find(any(Query.class), eq(LocationHistory.class))).thenReturn(List.of(history(1)));

        assertThat(service.history(DELIVERY_ID, 200).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("does not accept the delivery-service driver key as an identity")
    void refusesTheDeliveryServiceDriverKey() {
        authenticateAs(FleetRole.DRIVER, DRIVER_KEY);

        Throwable thrown = catchThrowable(() -> service.history(DELIVERY_ID, 200));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verify(mongoTemplate, never()).find(any(Query.class), eq(LocationHistory.class));
    }

    @Test
    @DisplayName("refuses every driver while the delivery has no driverUserId yet")
    void refusesAnyDriverForAnUnassignedDelivery() {
        DeliveryTrackingState state = givenUnassignedDelivery();
        authenticateAs(FleetRole.DRIVER, DRIVER_USER_ID);

        Throwable thrown = catchThrowable(() -> service.history(DELIVERY_ID, 200));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(state.getDriverUserId()).isNull();
        verify(mongoTemplate, never()).find(any(Query.class), eq(LocationHistory.class));
    }

    @Test
    @DisplayName("still lets the owning customer and operations follow an unassigned delivery")
    void allowsTheOwnerAndStaffForAnUnassignedDelivery() {
        givenUnassignedDelivery();
        when(mongoTemplate.find(any(Query.class), eq(LocationHistory.class))).thenReturn(List.of(history(1)));

        authenticateAs(FleetRole.CUSTOMER, CUSTOMER_ID);
        assertThat(service.history(DELIVERY_ID, 200).count()).isEqualTo(1);

        authenticateAs(FleetRole.OPERATIONS, 2L);
        assertThat(service.history(DELIVERY_ID, 200).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("refuses a customer who is neither the buyer nor the driver")
    void refusesAStranger() {
        authenticateAs(FleetRole.CUSTOMER, CUSTOMER_ID + 1);

        Throwable thrown = catchThrowable(() -> service.history(DELIVERY_ID, 200));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verify(mongoTemplate, never()).find(any(Query.class), eq(LocationHistory.class));
    }

    @Test
    @DisplayName("resolves the live map pins in one Redis round trip")
    void resolvesActivePinsInOneRoundTrip() {
        when(mongoTemplate.find(any(Query.class), eq(DeliveryTrackingState.class)))
                .thenReturn(List.of(state(DELIVERY_ID, true, NOW.minusSeconds(30))));
        when(latestLocationStore.findAllFor(any())).thenReturn(Map.of(DELIVERY_ID, location()));

        var active = service.active();

        verify(latestLocationStore).findAllFor(List.of(DELIVERY_ID));
        assertThat(active).hasSize(1);
        assertThat(active.get(0).online()).isTrue();
        assertThat(active.get(0).latestLocation()).isEqualTo(location());
    }

    @Test
    @DisplayName("reports a delivery with a stale fix as offline")
    void reportsAStaleFixAsOffline() {
        when(mongoTemplate.find(any(Query.class), eq(DeliveryTrackingState.class)))
                .thenReturn(List.of(state(DELIVERY_ID, true, NOW.minusSeconds(600))));
        when(latestLocationStore.findAllFor(any())).thenReturn(Map.of());

        var active = service.active();

        assertThat(active.get(0).online()).isFalse();
        assertThat(active.get(0).latestLocation()).isNull();
    }

    @Test
    @DisplayName("refuses to open a stream for a finished delivery")
    void refusesAStreamForAFinishedDelivery() {
        DeliveryTrackingState state = state(DELIVERY_ID, false, NOW.minusSeconds(30));
        state.setStatus(DeliveryStatus.DELIVERED);
        when(mongoTemplate.findById(DELIVERY_ID, DeliveryTrackingState.class)).thenReturn(state);

        Throwable thrown = catchThrowable(() -> service.requireStreamable(DELIVERY_ID));

        assertThat(((BusinessException) thrown).getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("sorts the live map by delivery id")
    void sortsTheLiveMap() {
        when(mongoTemplate.find(any(Query.class), eq(DeliveryTrackingState.class))).thenReturn(List.of());
        when(latestLocationStore.findAllFor(any())).thenReturn(Map.of());

        service.active();

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(query.capture(), eq(DeliveryTrackingState.class));
        assertThat(query.getValue().getSortObject()).containsEntry("deliveryId", 1);
    }

    private void givenState() {
        when(mongoTemplate.findById(DELIVERY_ID, DeliveryTrackingState.class))
                .thenReturn(state(DELIVERY_ID, true, NOW.minusSeconds(30)));
    }

    /** A delivery the delivery-service created but has not handed to a driver yet. */
    private DeliveryTrackingState givenUnassignedDelivery() {
        DeliveryTrackingState state = state(DELIVERY_ID, true, NOW.minusSeconds(30));
        state.setDriverId(null);
        state.setDriverUserId(null);
        state.setDriverName(null);
        when(mongoTemplate.findById(DELIVERY_ID, DeliveryTrackingState.class)).thenReturn(state);
        return state;
    }

    private void authenticateAs(FleetRole role, Long userId) {
        JwtPrincipal principal = new JwtPrincipal(userId, role.name().toLowerCase() + "@fleetflow.local", role);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private DeliveryTrackingState state(long deliveryId, boolean trackingEnabled, Instant lastLocationAt) {
        DeliveryTrackingState state = new DeliveryTrackingState();
        state.setDeliveryId(deliveryId);
        state.setOrderId(deliveryId);
        state.setCustomerId(CUSTOMER_ID);
        state.setDriverId(DRIVER_KEY);
        state.setDriverUserId(DRIVER_USER_ID);
        state.setDriverName("Yassine Ben Salah");
        state.setStatus(DeliveryStatus.IN_TRANSIT);
        state.setDestination("12 Rue de la LibertÃ©, La Marsa");
        state.setCity("La Marsa");
        state.setTrackingEnabled(trackingEnabled);
        state.setLastLocationAt(lastLocationAt);
        state.setLocationCount(25);
        return state;
    }

    private LocationHistory history(int step) {
        LocationHistory history = new LocationHistory();
        history.setId("fix-" + step);
        history.setDeliveryId(DELIVERY_ID);
        history.setDriverId(DRIVER_KEY);
        history.setCustomerId(CUSTOMER_ID);
        history.setLatitude(36.8065);
        history.setLongitude(10.1815);
        history.setSpeedKph(42.5);
        history.setHeading(275.0);
        history.setRecordedAt(NOW.minusSeconds(600L - step * 120L));
        history.setReceivedAt(history.getRecordedAt().plusMillis(250));
        return history;
    }

    private LocationResponse location() {
        return new LocationResponse(DELIVERY_ID, DRIVER_KEY, CUSTOMER_ID, 36.8065, 10.1815, 42.5, 275.0,
                NOW.minusSeconds(30), NOW.minusSeconds(29));
    }
}
