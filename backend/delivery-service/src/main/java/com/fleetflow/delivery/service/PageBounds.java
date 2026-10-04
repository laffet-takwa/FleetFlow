package com.fleetflow.delivery.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;

/**
 * Turns the raw {@code page} / {@code size} query parameters of the three list endpoints
 * into a {@link Pageable}, or refuses them.
 *
 * <p>{@code PageRequest.of} throws a plain {@link IllegalArgumentException} for a negative
 * index or a non-positive size, and the shared exception handler has no mapping for it, so
 * {@code ?size=-1} would answer 500 rather than 400. It also accepts any size at all, which
 * would let one request ask the database for the entire table. Both are refused here
 * instead, where the caller can be told what the bounds are.
 */
final class PageBounds {

    /** Matches the cap already applied to a driver's own delivery list. */
    static final int MAX_SIZE = 200;

    private PageBounds() {
    }

    static Pageable of(int page, int size, Sort sort) {
        if (page < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "page must be zero or greater");
        }
        if (size < 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "size must be at least 1");
        }
        if (size > MAX_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "size must not exceed " + MAX_SIZE);
        }
        return PageRequest.of(page, size, sort);
    }
}