package com.fleetflow.delivery.entity;

import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;

/** Availability of a vehicle; {@link #MAINTENANCE} keeps it out of assignment. */
public enum VehicleStatus {

    AVAILABLE,
    IN_USE,
    MAINTENANCE;

    public static VehicleStatus from(String value) {
        if (value != null) {
            for (VehicleStatus status : values()) {
                if (status.name().equalsIgnoreCase(value.trim())) {
                    return status;
                }
            }
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "Unknown vehicle status '" + value + "'");
    }
}
