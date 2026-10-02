package com.fleetflow.tracking.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.exception.ResourceNotFoundException;
import com.fleetflow.common.security.FleetRole;
import com.fleetflow.common.security.JwtPrincipal;
import com.fleetflow.common.security.SecurityUtils;
import com.fleetflow.tracking.config.FleetFlowTrackingProperties;
import com.fleetflow.tracking.document.DeliveryStatus;
import com.fleetflow.tracking.document.DeliveryTrackingState;
import com.fleetflow.tracking.document.LocationHistory;
import com.fleetflow.tracking.dto.LocationResponse;
import com.fleetflow.tracking.dto.TrackedDeliveryResponse;
import com.fleetflow.tracking.dto.TrackingHistoryResponse;
import com.fleetflow.tracking.redis.LatestLocationStore;

/**
 * Read side of the tracking API.
 *
 * <p>Authorisation lives here rather than in the controller so every entry point, including
 * the SSE stream, applies exactly the same rule: staff, the customer who ordered, or the
 * driver assigned to the delivery.
 */
@Service
public class TrackingQueryService {

    /**
     * Upper bound on a single history request. The API is a live feed, not an export, and
     * an unbounded scan of a year of positions is the one query that could hurt Mongo.
     */
    static final int MAX_HISTORY_LIMIT = 1000;

    private final MongoTemplate mongoTemplate;
    private final LatestLocationStore latestLocationStore;
    private final FleetFlowTrackingProperties properties;
    private final Clock clock;

    public TrackingQueryService(MongoTemplate mongoTemplate, LatestLocationStore latestLocationStore,
            FleetFlowTrackingProperties properties, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.latestLocationStore = latestLocationStore;
        this.properties = properties;
        this.clock = clock;
    }

    public LocationResponse latest(long deliveryId) {
        DeliveryTrackingState state = requireState(deliveryId);
        requireViewer(state);
        LocationResponse cached = latestLocationStore.get(deliveryId).orElse(null);
        if (cached != null) {
            return cached;
        }
        // Redis is a read model with a TTL, so after an expiry the trail is the fallback.
        LocationHistory newest = mongoTemplate.findOne(newestFirstQuery(deliveryId, 1), LocationHistory.class);
        if (newest == null) {
            throw ResourceNotFoundException.of("Location for delivery", deliveryId);
        }
        return toResponse(newest);
    }

    public TrackingHistoryResponse history(long deliveryId, int requestedLimit) {
        DeliveryTrackingState state = requireState(deliveryId);
        requireViewer(state);
        int limit = Math.max(1, Math.min(requestedLimit, MAX_HISTORY_LIMIT));
        // Ascending by recordedAt gives the caller the trail oldest first, ready to draw.
        Query query = Query.query(Criteria.where("deliveryId").is(deliveryId))
                .with(Sort.by(Sort.Direction.ASC, "recordedAt"))
                .limit(limit);
        List<LocationResponse> locations = mongoTemplate.find(query, LocationHistory.class).stream()
                .map(TrackingQueryService::toResponse)
                .toList();
        return new TrackingHistoryResponse(deliveryId, locations.size(), locations);
    }

    public TrackedDeliveryResponse status(long deliveryId) {
        DeliveryTrackingState state = requireState(deliveryId);
        requireViewer(state);
        return toTrackedDelivery(state,
                latestLocationStore.get(deliveryId).orElseGet(() -> newestStoredLocation(deliveryId)));
    }

    /**
     * Everything currently on the move, with pins resolved in one Redis round trip rather
     * than one call per row.
     */
    public List<TrackedDeliveryResponse> active() {
        List<DeliveryTrackingState> states = mongoTemplate.find(
                Query.query(Criteria.where("trackingEnabled").is(true)).with(Sort.by("deliveryId")),
                DeliveryTrackingState.class);
        Map<Long, LocationResponse> locations = latestLocationStore.findAllFor(
                states.stream().map(DeliveryTrackingState::getDeliveryId).toList());
        return states.stream().map(state -> toTrackedDelivery(state, locations.get(state.getDeliveryId()))).toList();
    }

    /**
     * Guard for the stream endpoint: a browser must not hold an emitter open for a
     * delivery that will never produce another event.
     */
    public void requireStreamable(long deliveryId) {
        DeliveryTrackingState state = requireState(deliveryId);
        requireViewer(state);
        if (!state.isTrackingEnabled() || DeliveryStatus.isTerminal(state.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Tracking is only available for an active delivery");
        }
    }

    DeliveryTrackingState requireState(long deliveryId) {
        DeliveryTrackingState state = mongoTemplate.findById(deliveryId, DeliveryTrackingState.class);
        if (state == null) {
            throw ResourceNotFoundException.of("Tracked delivery", deliveryId);
        }
        return state;
    }

    /**
     * Staff, the owning customer, or the assigned driver. The driver branch compares the
     * JWT subject to {@code driverUserId}, the platform identity; {@code driverId} is the
     * delivery-service key and identifies nobody. An unassigned delivery has no
     * {@code driverUserId} and therefore matches no driver at all. See the same note on
     * {@code LocationIngestionService}.
     */
    void requireViewer(DeliveryTrackingState state) {
        JwtPrincipal principal = SecurityUtils.requirePrincipal();
        if (principal.role() == FleetRole.ADMIN || principal.role() == FleetRole.OPERATIONS) {
            return;
        }
        if (Objects.equals(principal.userId(), state.getCustomerId())) {
            return;
        }
        if (state.getDriverUserId() != null && Objects.equals(principal.userId(), state.getDriverUserId())) {
            return;
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "You may only track your own deliveries");
    }

    private TrackedDeliveryResponse toTrackedDelivery(DeliveryTrackingState state, LocationResponse latest) {
        return new TrackedDeliveryResponse(
                state.getDeliveryId(),
                state.getOrderId(),
                state.getCustomerId(),
                state.getDriverId(),
                state.getDriverName(),
                state.getStatus(),
                state.getDestination(),
                state.getCity(),
                latest,
                state.getLocationCount(),
                isOnline(state, clock.instant()),
                state.getLastLocationAt());
    }

    private boolean isOnline(DeliveryTrackingState state, Instant now) {
        if (state.getLastLocationAt() == null) {
            return false;
        }
        Duration staleAfter = Duration.ofSeconds(properties.getStaleAfterSeconds());
        return state.getLastLocationAt().isAfter(now.minus(staleAfter));
    }

    private LocationResponse newestStoredLocation(long deliveryId) {
        LocationHistory newest = mongoTemplate.findOne(newestFirstQuery(deliveryId, 1), LocationHistory.class);
        return newest == null ? null : toResponse(newest);
    }

    private Query newestFirstQuery(long deliveryId, int limit) {
        return Query.query(Criteria.where("deliveryId").is(deliveryId))
                .with(Sort.by(Sort.Direction.DESC, "recordedAt"))
                .limit(limit);
    }

    private static LocationResponse toResponse(LocationHistory history) {
        return new LocationResponse(
                history.getDeliveryId(),
                history.getDriverId(),
                history.getCustomerId(),
                history.getLatitude(),
                history.getLongitude(),
                history.getSpeedKph(),
                history.getHeading(),
                history.getRecordedAt(),
                history.getReceivedAt());
    }
}
