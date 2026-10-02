package com.fleetflow.tracking.redis;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.tracking.config.FleetFlowTrackingProperties;
import com.fleetflow.tracking.dto.LocationResponse;

/**
 * Read model for the newest known position of every delivery.
 *
 * <p>MongoDB owns the durable trail but querying it for the current pin on each map
 * refresh is wasteful, so the latest point is mirrored here with a short TTL. The TTL is
 * the safety net: if ingestion stops, a forgotten key expires instead of pinning a van to
 * a stale coordinate forever.
 *
 * <p>Every Redis access in the service goes through this bean so the key template and the
 * serialisation format are defined exactly once.
 */
@Component
public class LatestLocationStore {

    private static final Logger log = LoggerFactory.getLogger(LatestLocationStore.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final FleetFlowTrackingProperties properties;

    public LatestLocationStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
            FleetFlowTrackingProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void put(LocationResponse location) {
        String value = serialise(location);
        try {
            redisTemplate.opsForValue().set(key(location.deliveryId()), value,
                    properties.getLatestLocationTtl());
        } catch (RuntimeException ex) {
            // A lost cache entry only means the next read falls back to MongoDB, so the
            // position itself is still accepted; the caller must not see a failure here.
            log.warn("Could not cache the latest location for delivery {}: {}", location.deliveryId(),
                    ex.getMessage());
        }
    }

    public Optional<LocationResponse> get(long deliveryId) {
        String value = redisTemplate.opsForValue().get(key(deliveryId));
        if (value == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(deserialise(value, deliveryId));
    }

    public void delete(long deliveryId) {
        redisTemplate.delete(key(deliveryId));
    }

    /**
     * Resolves many deliveries at once. The live map needs a pin for every tracked
     * delivery, so a single round trip replaces what would otherwise be N GETs.
     *
     * @return entries only for deliveries that currently have a cached position
     */
    public Map<Long, LocationResponse> findAllFor(Collection<Long> deliveryIds) {
        if (deliveryIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = List.copyOf(deliveryIds);
        List<String> keys = ids.stream().map(this::key).toList();
        List<String> values = redisTemplate.opsForValue().multiGet(keys);
        if (values == null) {
            return Map.of();
        }
        Map<Long, LocationResponse> resolved = new LinkedHashMap<>();
        for (int i = 0; i < ids.size() && i < values.size(); i++) {
            String value = values.get(i);
            if (value == null) {
                continue;
            }
            LocationResponse location = deserialise(value, ids.get(i));
            if (location != null) {
                resolved.put(ids.get(i), location);
            }
        }
        return resolved;
    }

    String key(long deliveryId) {
        return String.format(properties.getLatestLocationKey(), deliveryId);
    }

    private String serialise(LocationResponse location) {
        try {
            return objectMapper.writeValueAsString(location);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Could not serialise the location", ex);
        }
    }

    private LocationResponse deserialise(String json, long deliveryId) {
        try {
            return objectMapper.readValue(json, LocationResponse.class);
        } catch (JsonProcessingException ex) {
            // The cached value is disposable; a corrupt or stale-format entry is dropped
            // rather than propagated to the caller.
            log.warn("Discarding unreadable cached location for delivery {}: {}", deliveryId,
                    ex.getOriginalMessage());
            return null;
        }
    }
}
