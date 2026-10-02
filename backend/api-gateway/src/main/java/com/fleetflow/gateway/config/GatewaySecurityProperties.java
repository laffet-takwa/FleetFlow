package com.fleetflow.gateway.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Paths that bypass token verification.
 *
 * <p>{@code alwaysOpenPaths} are infrastructure endpoints that must stay reachable
 * even when a token is present but broken, so that a stale token cannot lock a user
 * out of the health check or the API documentation.
 */
@ConfigurationProperties(prefix = "fleetflow.gateway")
public class GatewaySecurityProperties {

    /** Endpoints reachable without any token, e.g. register and login. */
    private List<String> publicPaths = List.of("/api/auth/register", "/api/auth/login");

    /** Endpoints never subject to token verification, e.g. actuator and OpenAPI. */
    private List<String> alwaysOpenPaths = List.of("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**",
            "/swagger-ui.html");

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }

    public List<String> getAlwaysOpenPaths() {
        return alwaysOpenPaths;
    }

    public void setAlwaysOpenPaths(List<String> alwaysOpenPaths) {
        this.alwaysOpenPaths = alwaysOpenPaths;
    }
}