package com.fleetflow.tracking.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import com.fleetflow.common.correlation.CorrelationId;
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

/**
 * Accepts a position report and pushes it through the whole pipeline: durable history,
 * the Redis read model, then the live streams.
 *
 * <p>There is deliberately no {@code @Transactional} here. MongoDB and Redis are separate
 * stores with no shared transaction manager, so an annotation would only promise an
 * atomicity that cannot be delivered. Instead the order is chosen so a partial failure
 * degrades safely: the trail is written first, and Redis and the fan-out are derived from
 * it. A dropped cache entry costs one slower read; a dropped history document would cost
 * a permanent gap in the route.
 */
@Service
public class LocationIngestionService {

    private static final Logger log = LoggerFactory.getLogger(LocationIngestionService.class);

    private final MongoTemplate mongoTemplate;
    private final LatestLocationStore latestLocationStore;
    private final SseDeliveryRegistry sseDeliveryRegistry;
    private final FleetFlowTrackingProperties properties;
    private final Clock clock;

    public LocationIngestionService(MongoTemplate mongoTemplate, LatestLocationStore latestLocationStore,
            SseDeliveryRegistry sseDeliveryRegistry, FleetFlowTrackingProperties properties, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.latestLocationStore = latestLocationStore;
        this.sseDeliveryRegistry = sseDeliveryRegistry;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param authenticatedUserId JWT subject of the caller
     * @param staffCaller        true for ADMIN and OPERATIONS, who may post for anybody
     * @throws ResourceNotFoundException when the delivery is not tracked at all
     * @throws BusinessException         for a finished delivery, an unauthorised caller,
     *                                   out of order coordinates or an invalid position
     */
    public LocationResponse ingest(LocationUpdateRequest request, Long authenticatedUserId, boolean staffCaller) {
        DeliveryTrackingState state = mongoTemplate.findById(request.deliveryId(), DeliveryTrackingState.class);
        if (state == null) {
            throw ResourceNotFoundException.of("Tracked delivery", request.deliveryId());
        }
        requireTrackable(state);
        requireCoordinates(request);
        requireAuthorised(state, authenticatedUserId, staffCaller);

        // recordedAt is optional in the request: a driver posting from a browser or a
        // simple integration may omit it. It is defaulted to arrival time here, because
        // a null would otherwise propagate into the stored document, into the Redis
        // read model and into the SSE payload, where it surfaces as a NullPointerException
        // *after* the point has already been persisted — turning a successful write into
        // a 500 while still leaving the data in place.
        Instant recordedAt = request.recordedAt() == null ? clock.instant() : request.recordedAt();
        requireInOrder(state, recordedAt);

        Instant receivedAt = clock.instant();
        LocationHistory history = new LocationHistory();
        history.setDeliveryId(state.getDeliveryId());
        history.setDriverId(state.getDriverId());
        history.setCustomerId(state.getCustomerId());
        history.setLatitude(request.latitude());
        history.setLongitude(request.longitude());
        history.setSpeedKph(request.speedKph());
        history.setHeading(request.heading());
        history.setRecordedAt(recordedAt);
        history.setReceivedAt(receivedAt);
        mongoTemplate.save(history);

        state.setLocationCount(state.getLocationCount() + 1);
        state.setLastLocationAt(recordedAt);
        mongoTemplate.save(state);

        LocationResponse location = toResponse(state, request, receivedAt, recordedAt);
        latestLocationStore.put(location);
        sseDeliveryRegistry.broadcast(state.getDeliveryId(), location);

        log.info("Stored position {} for delivery {} by driver {} [correlationId={}]",
                recordedAt, state.getDeliveryId(), state.getDriverId(), CorrelationId.getOrCreate());
        return location;
    }

    private void requireTrackable(DeliveryTrackingState state) {
        if (!state.isTrackingEnabled() || DeliveryStatus.isTerminal(state.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Tracking is only available for an active delivery");
        }
    }

    /**
     * Bean validation already bounds the coordinates, but this service is also reached by
     * the seeder and by any future non web caller, so the configured maxima are enforced
     * here as the single authoritative rule.
     */
    private void requireCoordinates(LocationUpdateRequest request) {
        if (Math.abs(request.latitude()) > properties.getMaxLatitude()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "latitude must be between -" + properties.getMaxLatitude() + " and " + properties.getMaxLatitude());
        }
        if (Math.abs(request.longitude()) > properties.getMaxLongitude()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "longitude must be between -"
                    + properties.getMaxLongitude() + " and " + properties.getMaxLongitude());
        }
    }

    /**
     * Staff may post for any delivery, which is what the operations console needs. A
     * driver may only post for the delivery assigned to them.
     *
     * <p>The comparison is against {@code state.driverUserId}, the auth-service user id a
     * driver's JWT subject carries. {@code state.driverId} is the delivery-service primary
     * key and is never an identity here: the two id spaces are unrelated, so matching on it
     * would either reject every real driver or, worse, admit whichever customer or operator
     * happens to share that number. An unassigned delivery carries no {@code driverUserId}
     * at all and must match nobody.
     */
    private void requireAuthorised(DeliveryTrackingState state, Long authenticatedUserId, boolean staffCaller) {
        if (staffCaller) {
            return;
        }
        if (state.getDriverUserId() == null || !Objects.equals(authenticatedUserId, state.getDriverUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "You may only report positions for your own delivery");
        }
    }

    private void requireInOrder(DeliveryTrackingState state, Instant recordedAt) {
        if (state.getLastLocationAt() != null && !recordedAt.isAfter(state.getLastLocationAt())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Location update is out of order");
        }
    }

    private LocationResponse toResponse(DeliveryTrackingState state, LocationUpdateRequest request,
            Instant receivedAt, Instant recordedAt) {
        return new LocationResponse(
                state.getDeliveryId(),
                state.getDriverId(),
                state.getCustomerId(),
                request.latitude(),
                request.longitude(),
                request.speedKph(),
                request.heading(),
                recordedAt,
                receivedAt);
    }
}
