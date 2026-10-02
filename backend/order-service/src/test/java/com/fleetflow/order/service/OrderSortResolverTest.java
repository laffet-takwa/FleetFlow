package com.fleetflow.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.Sort;

import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;

@DisplayName("OrderSortResolver")
class OrderSortResolverTest {

    private final OrderSortResolver resolver = new OrderSortResolver();

    @Test
    @DisplayName("defaults to the newest orders first")
    void defaultsToCreatedAtDesc() {
        Sort sort = resolver.resolve(null);

        assertEquals(Sort.by(Sort.Direction.DESC, "createdAt"), sort);
        assertEquals(sort, resolver.resolve("   "));
    }

    @ParameterizedTest(name = "sort=[{0}] -> {1} {2}")
    @CsvSource({
            "createdAt, createdAt, ASC",
            "'createdAt,asc', createdAt, ASC",
            "'createdAt asc', createdAt, ASC",
            "'createdAt,desc', createdAt, DESC",
            "createdat, createdAt, ASC",
            "totalAmount, totalAmount, ASC",
            "'totalAmount,desc', totalAmount, DESC",
            "status, status, ASC",
            "'STATUS,asc', status, ASC" })
    void resolvesWhitelistedFields(String input, String property, String direction) {
        Sort sort = resolver.resolve(input);

        assertNotNull(sort.getOrderFor(property), () -> property + " should be the ordered property");
        assertEquals(direction, sort.getOrderFor(property).getDirection().name());
        assertTrue(sort.isSorted());
    }

    @ParameterizedTest(name = "sort=[{0}] is refused")
    @CsvSource({
            "id",
            "customerId",
            "'deliveryAddress; drop table orders'",
            "'createdAt,sideways'",
            "'createdAt,asc,status'" })
    void refusesAnythingOutsideTheWhitelist(String input) {
        BusinessException ex = assertThrows(BusinessException.class, () -> resolver.resolve(input));

        assertEquals(ErrorCode.VALIDATION_FAILED, ex.getErrorCode());
    }
}
