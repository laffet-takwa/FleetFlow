package com.fleetflow.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Optional Eureka registry.
 *
 * <p>The MVP routes every call by explicit URL, so this service is not started by
 * {@code docker compose up}. It exists to demonstrate the discovery-based variant:
 * start it with {@code EUREKA_ENABLED=true} and switch the gateway routes to
 * {@code lb://service-name}.
 */
@EnableEurekaServer
@SpringBootApplication
public class DiscoveryServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiscoveryServerApplication.class, args);
    }
}