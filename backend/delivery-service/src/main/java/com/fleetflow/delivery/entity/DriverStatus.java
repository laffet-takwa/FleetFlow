package com.fleetflow.delivery.entity;

import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;

/** Availability of a driver. {@link #ON_DELIVERY} is only ever set by operations. */
public enum DriverStatus {

    AVAILABLE,
    ON_DELIVERY,
    OFFLINE;

    public static DriverStatus from(String value) {
        if (value != null) {
            for (DriverStatus status : values()) {
                if (status.name().equalsIgnoreCase(value.trim())) {
                    return status;
                }
            }
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "Unknown driver status '" + value + "'");
    }
}
