package com.fleetflow.tracking.document;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Per delivery projection of everything the tracking UI needs, kept in step with the
 * delivery lifecycle purely from Kafka events.
 */
@Document("delivery_tracking_state")
public class DeliveryTrackingState {

    /**
     * The delivery-service delivery id, used as the Mongo {@code _id} so a redelivered
     * {@code delivery.assigned} event overwrites the existing projection instead of
     * creating a second one.
     */
    @Id
    private Long deliveryId;

    private Long orderId;

    private Long customerId;

    /**
     * Driver responsible for the delivery, in the delivery-service id space. Seeded demo
     * data deliberately uses the auth-service driver user ids (3..7) so a driver can post
     * their own positions; a production deployment that separates the two id spaces must
     * resolve the mapping before the authorisation check in the ingestion service.
     */
    private Long driverId;

    private String driverName;

    private String status;

    private String destination;

    private String city;

    /** Flipped off by a terminal event, which also closes the SSE stream. */
    private boolean trackingEnabled;

    private Instant activatedAt;

    private Instant lastLocationAt;

    private int locationCount;

    public DeliveryTrackingState() {
    }

    public static DeliveryTrackingState assign(Long deliveryId, Long orderId, Long customerId, Long driverId,
            String driverName, Instant activatedAt) {
        DeliveryTrackingState state = new DeliveryTrackingState();
        state.deliveryId = deliveryId;
        state.orderId = orderId;
        state.customerId = customerId;
        state.driverId = driverId;
        state.driverName = driverName;
        state.status = DeliveryStatus.ASSIGNED;
        state.trackingEnabled = true;
        state.activatedAt = activatedAt;
        return state;
    }

    public Long getDeliveryId() {
        return deliveryId;
    }

    public void setDeliveryId(Long deliveryId) {
        this.deliveryId = deliveryId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public String getDriverName() {
        return driverName;
    }

    public void setDriverName(String driverName) {
        this.driverName = driverName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public boolean isTrackingEnabled() {
        return trackingEnabled;
    }

    public void setTrackingEnabled(boolean trackingEnabled) {
        this.trackingEnabled = trackingEnabled;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public void setActivatedAt(Instant activatedAt) {
        this.activatedAt = activatedAt;
    }

    public Instant getLastLocationAt() {
        return lastLocationAt;
    }

    public void setLastLocationAt(Instant lastLocationAt) {
        this.lastLocationAt = lastLocationAt;
    }

    public int getLocationCount() {
        return locationCount;
    }

    public void setLocationCount(int locationCount) {
        this.locationCount = locationCount;
    }
}
