package com.fleetflow.tracking.sse;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fleetflow.tracking.config.FleetFlowTrackingProperties;
import com.fleetflow.tracking.dto.LocationResponse;
import com.fleetflow.tracking.redis.LatestLocationStore;

/**
 * Fan-out registry for live tracking streams.
 *
 * <p>SSE emitters have to be held in memory for the life of the connection, so this map is
 * the only state the service keeps outside MongoDB and Redis. It is per instance, which is
 * fine for the demo topology: every browser is connected to one tracking instance through
 * the gateway. A horizontally scaled deployment would need a Redis pub/sub relay here.
 *
 * <p>Every mutation goes through {@link #lock} so a broadcast never iterates a set that a
 * completion callback is concurrently removing from.
 */
@Component
public class SseDeliveryRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseDeliveryRegistry.class);

    /** Event a browser receives as soon as it subscribes, carrying the current pin. */
    private static final String EVENT_CONNECTED = "connected";

    /** Event carrying every subsequent position update. */
    private static final String EVENT_LOCATION = "location";

    private final Map<Long, Set<SseEmitter>> subscribers = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    private final FleetFlowTrackingProperties properties;
    private final LatestLocationStore latestLocationStore;

    public SseDeliveryRegistry(FleetFlowTrackingProperties properties, LatestLocationStore latestLocationStore) {
        this.properties = properties;
        this.latestLocationStore = latestLocationStore;
    }

    /**
     * Registers a stream for {@code deliveryId} and primes it with the position the
     * browser missed while it was connecting, so a page opened mid-journey renders
     * immediately instead of waiting for the next ping.
     */
    public SseEmitter subscribe(long deliveryId) {
        SseEmitter emitter = createEmitter(properties.getSseTimeout().toMillis());
        emitter.onCompletion(() -> unregister(deliveryId, emitter));
        emitter.onTimeout(() -> {
            unregister(deliveryId, emitter);
            emitter.complete();
        });
        emitter.onError(throwable -> unregister(deliveryId, emitter));

        lock.lock();
        try {
            subscribers.computeIfAbsent(deliveryId, key -> new HashSet<>()).add(emitter);
        } finally {
            lock.unlock();
        }

        // Best effort: the emitter buffers this until Spring attaches it to the response,
        // and a failure here must not stop the stream from opening.
        latestLocationStore.get(deliveryId)
                .ifPresent(location -> write(deliveryId, emitter, EVENT_CONNECTED, location));
        return emitter;
    }

    /**
     * Pushes a position to every open stream for the delivery. A browser that has gone
     * away fails on write and is dropped; one slow client must not hold up the others.
     */
    public void broadcast(long deliveryId, LocationResponse location) {
        for (SseEmitter emitter : snapshot(deliveryId)) {
            write(deliveryId, emitter, EVENT_LOCATION, location);
        }
    }

    /**
     * Closes every stream for the delivery, used when a terminal event arrives so the
     * browser ends the connection instead of waiting for its timeout.
     */
    public void complete(long deliveryId) {
        List<SseEmitter> emitters;
        lock.lock();
        try {
            Set<SseEmitter> removed = subscribers.remove(deliveryId);
            emitters = removed == null ? List.of() : List.copyOf(removed);
        } finally {
            lock.unlock();
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (RuntimeException ex) {
                log.debug("Could not complete the stream for delivery {}: {}", deliveryId, ex.getMessage());
            }
        }
    }

    public int subscriberCount(long deliveryId) {
        lock.lock();
        try {
            Set<SseEmitter> emitters = subscribers.get(deliveryId);
            return emitters == null ? 0 : emitters.size();
        } finally {
            lock.unlock();
        }
    }

    private List<SseEmitter> snapshot(long deliveryId) {
        lock.lock();
        try {
            Set<SseEmitter> emitters = subscribers.get(deliveryId);
            return emitters == null ? List.of() : List.copyOf(emitters);
        } finally {
            lock.unlock();
        }
    }

    private void unregister(long deliveryId, SseEmitter emitter) {
        lock.lock();
        try {
            Set<SseEmitter> emitters = subscribers.get(deliveryId);
            if (emitters == null) {
                return;
            }
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                subscribers.remove(deliveryId);
            }
        } finally {
            lock.unlock();
        }
    }

    private void write(long deliveryId, SseEmitter emitter, String eventName, LocationResponse location) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .id("loc-" + deliveryId + "-" + location.recordedAt().toEpochMilli())
                    .data(location, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ex) {
            // IllegalStateException means the stream was already completed, IOException that
            // the browser is gone: either way this emitter is dead, so stop writing to it.
            log.debug("Dropping the stream of delivery {} after a failed {} event: {}", deliveryId, eventName,
                    ex.getMessage());
            unregister(deliveryId, emitter);
        }
    }

    /**
     * Seam for tests: a real emitter writes straight to the servlet response, which does
     * not exist until Spring wires it up, so tests substitute a recording subclass.
     */
    protected SseEmitter createEmitter(long timeoutMillis) {
        return new SseEmitter(timeoutMillis);
    }
}
