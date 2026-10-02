package com.fleetflow.warehouse.service;

import com.fleetflow.warehouse.entity.StockStatus;

/**
 * The single definition of how a stock level reads.
 *
 * <p>Everything that needs a badge — product listings, the inventory grid, the
 * low-stock watch list, and the {@code WHERE} clause behind the stock status filter —
 * goes through here. Both the derived value and the numeric bounds come from the same
 * three ranges, so a filter can never disagree with the badge it is filtering on.
 */
public final class StockStatusResolver {

    /** Highest level that still reads as LOW_STOCK. */
    public static final int LOW_STOCK_THRESHOLD = 10;

    private static final Range OUT_OF_STOCK = new Range(0, 1);
    private static final Range LOW_STOCK = new Range(1, LOW_STOCK_THRESHOLD + 1);
    private static final Range IN_STOCK = new Range(LOW_STOCK_THRESHOLD + 1, Integer.MAX_VALUE);

    private StockStatusResolver() {
    }

    /**
     * @param availableQuantity units free to sell; never negative in practice, the
     *                          database CHECK constraint is what guarantees that
     */
    public static StockStatus resolve(int availableQuantity) {
        if (availableQuantity < OUT_OF_STOCK.toExclusive()) {
            return StockStatus.OUT_OF_STOCK;
        }
        if (availableQuantity < LOW_STOCK.toExclusive()) {
            return StockStatus.LOW_STOCK;
        }
        return StockStatus.IN_STOCK;
    }

    /** Null-safe variant for entity columns that may still be unset. */
    public static StockStatus resolve(Integer availableQuantity) {
        return resolve(availableQuantity == null ? 0 : availableQuantity);
    }

    /** The quantity band a status covers, for use as a query predicate. */
    public static Range bounds(StockStatus status) {
        return switch (status) {
            case OUT_OF_STOCK -> OUT_OF_STOCK;
            case LOW_STOCK -> LOW_STOCK;
            case IN_STOCK -> IN_STOCK;
        };
    }

    /**
     * Half open quantity band {@code [fromInclusive, toExclusive)}.
     *
     * @param toExclusive {@link Integer#MAX_VALUE} for the open ended top band, which
     *                    callers must render as {@code >= from} rather than a BETWEEN
     */
    public record Range(int fromInclusive, int toExclusive) {

        public boolean isUnboundedAbove() {
            return toExclusive == Integer.MAX_VALUE;
        }

        /** Exclusive upper bound as a level, for queries that cannot express MAX_VALUE. */
        public int toInclusive() {
            return isUnboundedAbove() ? Integer.MAX_VALUE : toExclusive - 1;
        }
    }
}