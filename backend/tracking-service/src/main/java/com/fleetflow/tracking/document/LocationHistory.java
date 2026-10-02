package com.fleetflow.tracking.document;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One GPS fix as reported by a driver's handset. The collection is append only: the newest
 * position is additionally cached in Redis, this is the durable trail behind it.
 */
@Document("location_history")
@CompoundIndex(name = "delivery_recorded_at_idx", def = "{'deliveryId': 1, 'recordedAt': -1}")
public class LocationHistory {

    @Id
    private String id;

    private Long deliveryId;

    private Long driverId;

    private Long customerId;

    private Double latitude;

    private Double longitude;

    private Double speedKph;

    private Double heading;

    /** When the handset took the reading; drives ordering and rejects late points. */
    private Instant recordedAt;

    /** When this service accepted the reading; the TTL index is anchored here. */
    @Indexed(name = "ttl_received_at", expireAfter = TTL_SECONDS)
    private Instant receivedAt;

    /**
     * The retention window has to be a compile time literal: {@code @Indexed} reads
     * {@code expireAfter} as a value, not as a bean expression, so it cannot read
     * {@code fleetflow.tracking.history-retention-days}. The value mirrors the property
     * default of 14 days; changing the property in a deployment also requires dropping and
     * recreating this index (see docs/tracking-service.md).
     */
    static final String TTL_SECONDS = "1209600";

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getDeliveryId() {
        return deliveryId;
    }

    public void setDeliveryId(Long deliveryId) {
        this.deliveryId = deliveryId;
    }

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public Double getSpeedKph() {
        return speedKph;
    }

    public void setSpeedKph(Double speedKph) {
        this.speedKph = speedKph;
    }

    public Double getHeading() {
        return heading;
    }

    public void setHeading(Double heading) {
        this.heading = heading;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(Instant recordedAt) {
        this.recordedAt = recordedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }
}
