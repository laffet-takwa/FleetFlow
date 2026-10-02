package com.fleetflow.tracking.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import com.fleetflow.tracking.config.FleetFlowTrackingProperties;
import com.fleetflow.tracking.dto.LocationResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("LatestLocationStore")
class LatestLocationStoreTest {

    private static final Duration TTL = Duration.ofHours(6);

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ObjectMapper objectMapper;
    private FleetFlowTrackingProperties properties;
    private LatestLocationStore store;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        properties = new FleetFlowTrackingProperties();
        properties.setLatestLocationTtl(TTL);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new LatestLocationStore(redisTemplate, objectMapper, properties);
    }

    @Test
    @DisplayName("writes the position under the configured key with the configured TTL")
    void writesUnderTheConfiguredKeyWithATtl() throws Exception {
        store.put(location(1024L));

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("delivery:1024:location"), value.capture(), eq(TTL));
        assertThat(objectMapper.readValue(value.getValue(), LocationResponse.class).deliveryId()).isEqualTo(1024L);
    }

    @Test
    @DisplayName("follows the key template when the deployment overrides it")
    void followsAnOverriddenKeyTemplate() {
        FleetFlowTrackingProperties custom = new FleetFlowTrackingProperties();
        custom.setLatestLocationKey("fleetflow:delivery:%d:pin");

        new LatestLocationStore(redisTemplate, objectMapper, custom).put(location(7L));

        verify(valueOperations).set(eq("fleetflow:delivery:7:pin"), any(), eq(TTL));
    }

    @Test
    @DisplayName("reads a cached position back")
    void readsACachedPosition() throws Exception {
        when(valueOperations.get("delivery:1024:location"))
                .thenReturn(objectMapper.writeValueAsString(location(1024L)));

        Optional<LocationResponse> found = store.get(1024L);

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().latitude()).isEqualTo(36.8065);
    }

    @Test
    @DisplayName("returns empty when nothing is cached")
    void returnsEmptyWhenNothingIsCached() {
        when(valueOperations.get("delivery:1024:location")).thenReturn(null);

        assertThat(store.get(1024L)).isEmpty();
    }

    @Test
    @DisplayName("drops an unreadable cache entry instead of failing the request")
    void dropsAnUnreadableEntry() {
        when(valueOperations.get("delivery:1024:location")).thenReturn("{not json");

        assertThat(store.get(1024L)).isEmpty();
    }

    @Test
    @DisplayName("resolves many deliveries in a single round trip")
    void resolvesManyDeliveriesInOneRoundTrip() throws Exception {
        when(valueOperations.multiGet(List.of("delivery:1:location", "delivery:2:location", "delivery:3:location")))
                .thenReturn(Arrays.asList(
                        objectMapper.writeValueAsString(location(1L)),
                        null,
                        objectMapper.writeValueAsString(location(3L))));

        Map<Long, LocationResponse> resolved = store.findAllFor(List.of(1L, 2L, 3L));

        assertThat(resolved).containsOnlyKeys(1L, 3L);
        verify(valueOperations).multiGet(List.of("delivery:1:location", "delivery:2:location",
                "delivery:3:location"));
    }

    @Test
    @DisplayName("does not call Redis at all for an empty batch")
    void skipsRedisForAnEmptyBatch() {
        assertThat(store.findAllFor(List.of())).isEmpty();

        verify(valueOperations, never()).multiGet(any());
    }

    @Test
    @DisplayName("removes the entry when tracking stops")
    void removesTheEntry() {
        store.delete(1024L);

        verify(redisTemplate).delete("delivery:1024:location");
    }

    @Test
    @DisplayName("keeps accepting a point when the cache write fails")
    void survivesACacheFailure() {
        doThrow(new IllegalStateException("redis down")).when(valueOperations).set(any(), any(), eq(TTL));

        store.put(location(1024L));
    }

    private LocationResponse location(long deliveryId) {
        return new LocationResponse(deliveryId, 3L, 9L, 36.8065, 10.1815, 42.5, 275.0,
                Instant.parse("2026-10-02T14:00:00Z"), Instant.parse("2026-10-02T14:00:01Z"));
    }
}
