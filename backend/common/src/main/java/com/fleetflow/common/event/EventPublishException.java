package com.fleetflow.common.event;

/** Raised when an event could not be handed over to the broker. */
public class EventPublishException extends RuntimeException {

    public EventPublishException(String message) {
        super(message);
    }

    public EventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}