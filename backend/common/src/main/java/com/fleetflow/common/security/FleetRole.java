package com.fleetflow.common.security;

/** Platform roles. Spring Security authorities are derived as {@code ROLE_<name>}. */
public enum FleetRole {

    ADMIN,
    OPERATIONS,
    DRIVER,
    CUSTOMER;

    public String authority() {
        return "ROLE_" + name();
    }

    public static FleetRole from(String value) {
        if (value == null) {
            return null;
        }
        String normalised = value.trim().toUpperCase().replace("ROLE_", "");
        for (FleetRole role : values()) {
            if (role.name().equals(normalised)) {
                return role;
            }
        }
        return null;
    }
}