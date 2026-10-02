package com.fleetflow.common.exception;

/**
 * Base class for every deliberate business failure.
 *
 * <p>Thrown from services and mapped to a response by
 * {@link com.fleetflow.common.api.GlobalExceptionHandler}. Anything that escapes as a
 * different exception type is reported as {@link ErrorCode#INTERNAL_ERROR} and logged
 * with the correlation id, so raw stack traces never reach a client.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}