package com.fleetflow.tracking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.fleetflow.tracking.config.FleetFlowTrackingProperties;

/**
 * Real-time core of the platform: driver positions land here, the newest one is kept in
 * Redis, the full trail in MongoDB and browsers are fanned out to over SSE.
 */
@SpringBootApplication
@EnableConfigurationProperties(FleetFlowTrackingProperties.class)
public class TrackingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrackingServiceApplication.class, args);
    }
}
