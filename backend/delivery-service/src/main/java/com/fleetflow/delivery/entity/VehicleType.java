package com.fleetflow.delivery.entity;

import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;

public enum VehicleType {

    VAN,
    MOTORCYCLE,
    TRUCK,
    CAR;

    public static VehicleType from(String value) {
        if (value != null) {
            for (VehicleType type : values()) {
                if (type.name().equalsIgnoreCase(value.trim())) {
                    return type;
                }
            }
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "Unknown vehicle type '" + value + "'");
    }
}
