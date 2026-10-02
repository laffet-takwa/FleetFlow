package com.fleetflow.common.api;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import com.fleetflow.common.correlation.CorrelationId;
import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;

/**
 * Translates exceptions into the platform wide {@link ApiErrorResponse} contract.
 *
 * <p>Unexpected exceptions are logged with their correlation id and replaced by a
 * generic message, so stack traces and SQL fragments are never exposed to clients.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusiness(BusinessException ex, HttpServletRequest request) {
        ErrorCode code = ex.getErrorCode();
        log.warn("Business error [{}] {} {} -> {}", code, request.getMethod(), request.getRequestURI(), ex.getMessage());
        return build(code, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidBody(MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        List<ApiErrorResponse.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiErrorResponse.FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, "Request validation failed", request, violations);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
            HttpServletRequest request) {
        List<ApiErrorResponse.FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(violation -> new ApiErrorResponse.FieldViolation(
                        lastNode(violation), violation.getMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, "Request validation failed", request, violations);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex,
            HttpServletRequest request) {
        return build(ErrorCode.BAD_REQUEST, "Request body is missing or not valid JSON", request, List.of());
    }

    @ExceptionHandler({ MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class })
    public ResponseEntity<ApiErrorResponse> handleBadParameter(Exception ex, HttpServletRequest request) {
        return build(ErrorCode.BAD_REQUEST, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleIntegrity(DataIntegrityViolationException ex,
            HttpServletRequest request) {
        log.warn("Constraint violation on {} {}: {}", request.getMethod(), request.getRequestURI(), rootMessage(ex));
        return build(ErrorCode.CONFLICT, "The request conflicts with the current state of the resource", request, List.of());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLock(OptimisticLockingFailureException ex,
            HttpServletRequest request) {
        return build(ErrorCode.CONFLICT, "The resource was modified concurrently, please retry", request, List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return build(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.defaultMessage(), request, List.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException ex,
            HttpServletRequest request) {
        return build(ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.defaultMessage(), request, List.of());
    }

    @ExceptionHandler({ NoHandlerFoundException.class, NoResourceFoundException.class })
    public ResponseEntity<ApiErrorResponse> handleNoHandler(Exception ex, HttpServletRequest request) {
        return build(ErrorCode.NOT_FOUND, "No endpoint " + request.getMethod() + " " + request.getRequestURI(),
                request, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {} [correlationId={}]", request.getMethod(), request.getRequestURI(),
                CorrelationId.get(), ex);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), request, List.of());
    }

    private ResponseEntity<ApiErrorResponse> build(ErrorCode code, String message, HttpServletRequest request,
            List<ApiErrorResponse.FieldViolation> violations) {
        ApiErrorResponse body = new ApiErrorResponse(
                Instant.now(),
                code.httpStatus().value(),
                code.name(),
                message,
                request.getRequestURI(),
                CorrelationId.getOrCreate(),
                violations);
        return ResponseEntity.status(code.httpStatus()).body(body);
    }

    private static String lastNode(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int index = path.lastIndexOf('.');
        return index >= 0 ? path.substring(index + 1) : path;
    }

    private static String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? ex.toString() : cursor.getMessage();
    }

    /** Exposed so services can build custom responses with the same shape. */
    public static ResponseEntity<ApiErrorResponse> status(HttpStatus status, String error, String message,
            HttpServletRequest request) {
        ApiErrorResponse body = new ApiErrorResponse(Instant.now(), status.value(), error, message,
                request.getRequestURI(), CorrelationId.getOrCreate(), List.of());
        return ResponseEntity.status(status).body(body);
    }
}