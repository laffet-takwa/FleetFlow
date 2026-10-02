package com.fleetflow.common.api;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * Transport shape for paginated collections, wrapping Spring Data's {@link Page}
 * without leaking its internal {@code Pageable} serialisation.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }

    public static <T, S> PageResponse<T> from(Page<S> page, List<T> mappedContent) {
        return new PageResponse<>(
                mappedContent,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }

    public static <T> PageResponse<T> empty(int page, int size) {
        return new PageResponse<>(List.of(), page, size, 0, 0, true, true);
    }
}