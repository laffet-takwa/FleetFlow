package com.fleetflow.common.security;

/**
 * The authenticated caller, stored as the Spring Security principal so that
 * {@link SecurityUtils} can expose it without touching the database.
 */
public record JwtPrincipal(Long userId, String email, FleetRole role) {

    @Override
    public String toString() {
        return "JwtPrincipal[userId=" + userId + ", email=" + email + ", role=" + role + "]";
    }
}