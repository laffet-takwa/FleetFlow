package com.fleetflow.common.exception;

/**
 * Raised when an aggregate is asked to move to a state its lifecycle does not allow,
 * for example {@code DELIVERED -> IN_TRANSIT}. Surfaces as HTTP 409.
 */
public class InvalidStateTransitionException extends BusinessException {

    public InvalidStateTransitionException(String aggregate, Object id, String from, String to) {
        super(ErrorCode.INVALID_STATE_TRANSITION,
                aggregate + " " + id + " cannot move from " + from + " to " + to);
    }
}