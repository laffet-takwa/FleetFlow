package com.fleetflow.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Single entry point for the FleetFlow platform.
 *
 * <p>Responsive by design: the gateway terminates client connections, verifies the
 * access token, and proxies to the business services. Server-Sent Events pass through
 * untouched so live tracking keeps streaming through the proxy hop.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}