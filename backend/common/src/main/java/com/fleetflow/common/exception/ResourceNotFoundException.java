package com.fleetflow.common.exception;

/** The requested aggregate does not exist. */
public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }

    public ResourceNotFoundException(String resource, Object id) {
        super(ErrorCode.NOT_FOUND, resource + " " + id + " was not found");
    }

    public static ResourceNotFoundException of(String resource, Object id) {
        return new ResourceNotFoundException(resource, id);
    }
}