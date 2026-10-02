package com.fleetflow.order.service;

import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;

/**
 * Translates a client supplied {@code sort} parameter into a Spring Data {@link Sort}.
 *
 * <p>Only the three whitelisted properties are accepted. Anything else is rejected
 * rather than ignored, because a silently dropped sort reads as a broken UI, and
 * because a raw user string must never reach the {@code ORDER BY} clause.
 */
@Component
public class OrderSortResolver {

    /** Lower-cased client alias to persistent property name. */
    private static final Map<String, String> SORTABLE = Map.of(
            "createdat", "createdAt",
            "totalamount", "totalAmount",
            "status", "status");

    private static final String DEFAULT_SORT = "createdAt";

    private static final Sort DEFAULT = Sort.by(Sort.Direction.DESC, DEFAULT_SORT);

    /** @param sort {@code property}, {@code property,asc} or {@code property desc}; null for the default */
    public Sort resolve(String sort) {
        if (sort == null || sort.isBlank()) {
            return DEFAULT;
        }
        String[] parts = sort.trim().split("[,\\s]+");
        if (parts.length > 2) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "sort accepts a single property, optionally with a direction");
        }
        String property = SORTABLE.get(parts[0].toLowerCase(Locale.ROOT));
        if (property == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "sort must be one of createdAt, totalAmount, status");
        }
        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length > 1) {
            direction = parseDirection(parts[1]);
        }
        return Sort.by(direction, property);
    }

    private Sort.Direction parseDirection(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "sort direction must be asc or desc");
        };
    }
}
