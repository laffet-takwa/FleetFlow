package com.fleetflow.tracking.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Stubber;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fleetflow.tracking.config.FleetFlowTrackingProperties;
import com.fleetflow.tracking.dto.LocationResponse;
import com.fleetflow.tracking.redis.LatestLocationStore;

@ExtendWith(MockitoExtension.class)
@DisplayName("SseDeliveryRegistry")
class SseDeliveryRegistryTest {

    private static final long DELIVERY_ID = 3L;

    @Mock
    private LatestLocationStore latestLocationStore;

    private FleetFlowTrackingProperties properties;
    private RecordingEmitter emitter;

    @BeforeEach
    void setUp() {
        properties = new FleetFlowTrackingProperties();
        emitter = new RecordingEmitter();
    }

    @Test
    @DisplayName("registers the stream and primes it with the current position")
    void registersAndPrimesTheStream() {
        when(latestLocationStore.get(DELIVERY_ID)).thenReturn(Optional.of(location()));
        SseDeliveryRegistry registry = registryFor(emitter);

        SseEmitter returned = registry.subscribe(DELIVERY_ID);

        assertThat(returned).isSameAs(emitter);
        assertThat(registry.subscriberCount(DELIVERY_ID)).isEqualTo(1);
        assertThat(emitter.events).hasSize(1);
        assertThat(emitter.frameOf(0)).contains("event:connected").contains("loc-3-");
    }

    @Test
    @DisplayName("opens the stream even when the delivery has no position yet")
    void registersWithoutAPosition() {
        when(latestLocationStore.get(DELIVERY_ID)).thenReturn(Optional.empty());
        SseDeliveryRegistry registry = registryFor(emitter);

        registry.subscribe(DELIVERY_ID);

        assertThat(registry.subscriberCount(DELIVERY_ID)).isEqualTo(1);
        assertThat(emitter.events).isEmpty();
    }

    @Test
    @DisplayName("broadcasts a position to the open streams")
    void broadcastsToOpenStreams() {
        when(latestLocationStore.get(DELIVERY_ID)).thenReturn(Optional.of(location()));
        SseDeliveryRegistry registry = registryFor(emitter);
        registry.subscribe(DELIVERY_ID);

        LocationResponse update = new LocationResponse(DELIVERY_ID, 3L, 9L, 36.9, 10.2, 30.0, 90.0,
                Instant.parse("2026-10-02T14:05:00Z"), Instant.parse("2026-10-02T14:05:01Z"));
        registry.broadcast(DELIVERY_ID, update);

        assertThat(emitter.events).hasSize(2);
        assertThat(emitter.frameOf(1)).contains("event:location");
        assertThat(emitter.payloadOf(1)).isEqualTo(update);
    }

    @Test
    @DisplayName("ignores a broadcast for a delivery nobody is watching")
    void ignoresBroadcastsWithoutSubscribers() {
        // No emitter seam needed: with nobody subscribed the real emitters are never reached.
        SseDeliveryRegistry registry = new SseDeliveryRegistry(properties, latestLocationStore);

        registry.broadcast(DELIVERY_ID, location());

        assertThat(registry.subscriberCount(DELIVERY_ID)).isZero();
    }

    @Test
    @DisplayName("delivers the same update to every open stream of the delivery")
    void broadcastsToAllOpenStreams() {
        RecordingEmitter second = new RecordingEmitter();
        when(latestLocationStore.get(DELIVERY_ID)).thenReturn(Optional.empty());
        SseDeliveryRegistry registry = registryFor(emitter, second);
        registry.subscribe(DELIVERY_ID);
        registry.subscribe(DELIVERY_ID);

        registry.broadcast(DELIVERY_ID, location());

        assertThat(registry.subscriberCount(DELIVERY_ID)).isEqualTo(2);
        assertThat(emitter.events).hasSize(1);
        assertThat(second.events).hasSize(1);
    }

    @Test
    @DisplayName("does not let a stream that has gone away affect the others")
    void keepsTheHealthyStreamsWhenOneFails() {
        RecordingEmitter second = new RecordingEmitter();
        when(latestLocationStore.get(DELIVERY_ID)).thenReturn(Optional.empty());
        SseDeliveryRegistry registry = registryFor(emitter, second);
        registry.subscribe(DELIVERY_ID);
        registry.subscribe(DELIVERY_ID);
        emitter.failNextWrite();

        registry.broadcast(DELIVERY_ID, location());

        assertThat(registry.subscriberCount(DELIVERY_ID)).isEqualTo(1);
        assertThat(second.events).hasSize(1);
    }

    @Test
    @DisplayName("closes every stream when the delivery is finished")
    void completesStreamsOnATerminalEvent() {
        when(latestLocationStore.get(DELIVERY_ID)).thenReturn(Optional.empty());
        SseDeliveryRegistry registry = registryFor(emitter);
        registry.subscribe(DELIVERY_ID);

        registry.complete(DELIVERY_ID);

        assertThat(emitter.completed).isTrue();
        assertThat(registry.subscriberCount(DELIVERY_ID)).isZero();
    }

    @Test
    @DisplayName("gives every stream the configured timeout")
    void usesTheConfiguredTimeout() {
        properties.setSseTimeout(Duration.ofMinutes(30));
        SseDeliveryRegistry registry = registryFor(emitter);
        when(latestLocationStore.get(DELIVERY_ID)).thenReturn(Optional.empty());

        registry.subscribe(DELIVERY_ID);

        verify(registry).createEmitter(Duration.ofMinutes(30).toMillis());
    }

    /**
     * A real emitter writes straight to the servlet response, which Spring only attaches
     * after the controller returns, so the seam in the registry is used to substitute
     * recorders and assert on the exact SSE frames that would have been written.
     */
    private SseDeliveryRegistry registryFor(RecordingEmitter first, RecordingEmitter... others) {
        SseDeliveryRegistry registry = spy(new SseDeliveryRegistry(properties, latestLocationStore));
        Stubber stubber = doReturn(first);
        for (RecordingEmitter other : others) {
            stubber = stubber.doReturn(other);
        }
        stubber.when(registry).createEmitter(anyLong());
        return registry;
    }

    private LocationResponse location() {
        return new LocationResponse(DELIVERY_ID, 3L, 9L, 36.8065, 10.1815, 42.5, 275.0,
                Instant.parse("2026-10-02T14:00:00Z"), Instant.parse("2026-10-02T14:00:01Z"));
    }

    private static final class RecordingEmitter extends SseEmitter {

        private final List<SseEventBuilder> events = new ArrayList<>();
        private boolean completed;
        private boolean failNextWrite;

        private RecordingEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failNextWrite) {
                failNextWrite = false;
                throw new IOException("browser is gone");
            }
            events.add(builder);
        }

        @Override
        public void complete() {
            completed = true;
            super.complete();
        }

        private void failNextWrite() {
            failNextWrite = true;
        }

        /** The SSE frame prefix ("event:", "id:", "data:") the builder writes ahead of the payload. */
        private String frameOf(int index) {
            return String.valueOf(events.get(index).build().iterator().next().getData());
        }

        private Object payloadOf(int index) {
            return events.get(index).build().stream()
                    .filter(part -> MediaType.APPLICATION_JSON.equals(part.getMediaType()))
                    .map(ResponseBodyEmitter.DataWithMediaType::getData)
                    .findFirst()
                    .orElseThrow();
        }
    }
}
