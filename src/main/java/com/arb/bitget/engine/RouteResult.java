package com.arb.bitget.engine;

import com.arb.bitget.model.RouteDirection;

import java.math.BigDecimal;

/**
 * Result of a route calculation for a single triangle.
 * Contains the best direction, expected profit, and leg details.
 *
 * <p>Thread safety: immutable record, safe for concurrent use.
 */
public record RouteResult(
        RouteDirection direction,
        BigDecimal profitBps,
        BigDecimal[] legPrices,
        BigDecimal[] legQuantities,
        boolean profitable
) {

    /**
     * Returns a sentinel result when prices are not yet available.
     */
    public static RouteResult noPrices() {
        return new RouteResult(null, BigDecimal.ZERO, new BigDecimal[0], new BigDecimal[0], false);
    }
}
