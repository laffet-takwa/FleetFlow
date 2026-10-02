package com.fleetflow.warehouse.service;

import java.util.List;
import java.util.Locale;

import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;

/**
 * The closed set of catalogue categories.
 *
 * <p>Stored as a String on the row so order-service can snapshot it across the wire,
 * but the set is enforced here: an unknown category would otherwise reach the
 * catalogue filter dropdown as a one-off entry that nothing else recognises.
 */
public final class ProductCategories {

    public static final String GROCERY = "GROCERY";
    public static final String ELECTRONICS = "ELECTRONICS";
    public static final String HOME = "HOME";
    public static final String BEAUTY = "BEAUTY";
    public static final String SPORTS = "SPORTS";
    public static final String STATIONERY = "STATIONERY";

    public static final List<String> ALL = List.of(GROCERY, ELECTRONICS, HOME, BEAUTY, SPORTS, STATIONERY);

    private ProductCategories() {
    }

    /**
     * @return the canonical upper-case category
     * @throws BusinessException with {@link ErrorCode#VALIDATION_FAILED} when the value is
     *         outside {@link #ALL}, rather than storing a category nothing else knows
     */
    public static String normalise(String rawCategory) {
        if (rawCategory == null || rawCategory.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "category is required");
        }
        String candidate = rawCategory.trim().toUpperCase(Locale.ROOT);
        if (!ALL.contains(candidate)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "category must be one of " + String.join(", ", ALL));
        }
        return candidate;
    }
}