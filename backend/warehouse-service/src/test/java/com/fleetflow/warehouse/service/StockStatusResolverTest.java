package com.fleetflow.warehouse.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fleetflow.warehouse.entity.StockStatus;

@DisplayName("StockStatusResolver")
class StockStatusResolverTest {

    @ParameterizedTest(name = "{0} available -> {1}")
    @CsvSource({
            "0,  OUT_OF_STOCK",
            "1,  LOW_STOCK",
            "9,  LOW_STOCK",
            "10, LOW_STOCK",
            "11, IN_STOCK",
            "40, IN_STOCK",
            "99999, IN_STOCK"
    })
    @DisplayName("maps a level to its badge, threshold included")
    void resolvesFromAvailableQuantity(int available, StockStatus expected) {
        assertEquals(expected, StockStatusResolver.resolve(available));
    }

    @Test
    @DisplayName("the low-stock band ends exactly at the named threshold")
    void thresholdIsInclusive() {
        assertEquals(10, StockStatusResolver.LOW_STOCK_THRESHOLD);
        assertEquals(StockStatus.LOW_STOCK, StockStatusResolver.resolve(StockStatusResolver.LOW_STOCK_THRESHOLD));
        assertEquals(StockStatus.IN_STOCK,
                StockStatusResolver.resolve(StockStatusResolver.LOW_STOCK_THRESHOLD + 1));
    }

    @Test
    @DisplayName("a null level reads as out of stock rather than throwing")
    void nullLevelIsOutOfStock() {
        assertEquals(StockStatus.OUT_OF_STOCK, StockStatusResolver.resolve((Integer) null));
    }

    @ParameterizedTest
    @ValueSource(ints = { Integer.MIN_VALUE, -1 })
    @DisplayName("a negative level is treated as empty")
    void negativeLevelIsOutOfStock(int available) {
        assertEquals(StockStatus.OUT_OF_STOCK, StockStatusResolver.resolve(available));
    }

    @Test
    @DisplayName("the filter bands cover the same levels the badge does, with no gaps or overlaps")
    void boundsPartitionTheRange() {
        StockStatusResolver.Range out = StockStatusResolver.bounds(StockStatus.OUT_OF_STOCK);
        StockStatusResolver.Range low = StockStatusResolver.bounds(StockStatus.LOW_STOCK);
        StockStatusResolver.Range in = StockStatusResolver.bounds(StockStatus.IN_STOCK);

        assertEquals(0, out.fromInclusive());
        assertEquals(1, out.toExclusive());
        assertEquals(1, low.fromInclusive());
        assertEquals(StockStatusResolver.LOW_STOCK_THRESHOLD + 1, low.toExclusive());
        assertEquals(StockStatusResolver.LOW_STOCK_THRESHOLD + 1, in.fromInclusive());
        assertTrue(in.isUnboundedAbove());

        for (int available = 0; available <= 200; available++) {
            StockStatusResolver.Range range = StockStatusResolver.bounds(StockStatusResolver.resolve(available));
            assertTrue(available >= range.fromInclusive()
                            && (range.isUnboundedAbove() || available < range.toExclusive()),
                    "available " + available + " must fall inside its own status band");
        }
    }
}