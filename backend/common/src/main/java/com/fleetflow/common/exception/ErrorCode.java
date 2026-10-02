package com.fleetflow.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Canonical error taxonomy for the whole platform. The {@code code} is what the
 * frontend switches on, the {@link HttpStatus} is what crosses the wire.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request"),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "Invalid state transition"),
    CONFLICT(HttpStatus.CONFLICT, "Conflicting state"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid email or password"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Access token has expired"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Access token is not valid"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "You are not allowed to perform this action"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    INSUFFICIENT_INVENTORY(HttpStatus.UNPROCESSABLE_ENTITY, "Not enough stock to fulfil this order"),
    UNPROCESSABLE(HttpStatus.UNPROCESSABLE_ENTITY, "Request could not be processed"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected internal error"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Downstream service unavailable");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    /** Spring's own {@code reason phrase} is used so the wire format matches Boot defaults. */
    public String reasonPhrase() {
        return httpStatus.getReasonPhrase();
    }
}