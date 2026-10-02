package com.fleetflow.tracking.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything tunable about the tracking pipeline, bound from {@code fleetflow.tracking.*}.
 *
 * <p>Defaults mirror {@code application.yml} so the service behaves identically when a
 * property is omitted, and so unit tests can instantiate the record without a context.
 */
@ConfigurationProperties(prefix = "fleetflow.tracking")
public class FleetFlowTrackingProperties {

    /** {@link String#format} template for the Redis key holding the newest position. */
    private String latestLocationKey = "delivery:%d:location";

    /** How long a cached position stays in Redis; must outlive the staleness window. */
    private Duration latestLocationTtl = Duration.ofHours(6);

    /** Days of location history kept in MongoDB before the TTL index removes a document. */
    private int historyRetentionDays = 14;

    /** Lifetime of a browser stream; the emitter completes on its own after this. */
    private Duration sseTimeout = Duration.ofMinutes(30);

    /** A delivery is reported as {@code online} when its last fix is younger than this. */
    private long staleAfterSeconds = 120;

    /** Upper bound for latitude, validated server side as well as by bean validation. */
    private double maxLatitude = 90.0;

    /** Upper bound for longitude, validated server side as well as by bean validation. */
    private double maxLongitude = 180.0;

    public String getLatestLocationKey() {
        return latestLocationKey;
    }

    public void setLatestLocationKey(String latestLocationKey) {
        this.latestLocationKey = latestLocationKey;
    }

    public Duration getLatestLocationTtl() {
        return latestLocationTtl;
    }

    public void setLatestLocationTtl(Duration latestLocationTtl) {
        this.latestLocationTtl = latestLocationTtl;
    }

    public int getHistoryRetentionDays() {
        return historyRetentionDays;
    }

    public void setHistoryRetentionDays(int historyRetentionDays) {
        this.historyRetentionDays = historyRetentionDays;
    }

    public Duration getSseTimeout() {
        return sseTimeout;
    }

    public void setSseTimeout(Duration sseTimeout) {
        this.sseTimeout = sseTimeout;
    }

    public long getStaleAfterSeconds() {
        return staleAfterSeconds;
    }

    public void setStaleAfterSeconds(long staleAfterSeconds) {
        this.staleAfterSeconds = staleAfterSeconds;
    }

    public double getMaxLatitude() {
        return maxLatitude;
    }

    public void setMaxLatitude(double maxLatitude) {
        this.maxLatitude = maxLatitude;
    }

    public double getMaxLongitude() {
        return maxLongitude;
    }

    public void setMaxLongitude(double maxLongitude) {
        this.maxLongitude = maxLongitude;
    }
}
