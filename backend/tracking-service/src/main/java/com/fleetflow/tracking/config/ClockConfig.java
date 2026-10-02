package com.fleetflow.tracking.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Every timestamp in this service is read from one {@link Clock} bean so tests can pin
 * "now" instead of asserting against a wall clock that keeps moving.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
