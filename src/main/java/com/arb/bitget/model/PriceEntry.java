package com.arb.bitget.model;

import java.math.BigDecimal;

/**
 * Immutable snapshot of a trading pair's best bid/ask prices.
 * Stored in PriceCache, read by ArbitrageEngine.
 *
 * <p>Thread safety: immutable record, safe for concurrent use.
 */
public record PriceEntry(
        BigDecimal bestBid,
        BigDecimal bestAsk,
        BigDecimal bidSize,
        BigDecimal askSize,
        long timestamp
) {

    /**
     * Validates that bid and ask prices are present and positive.
     */
    public PriceEntry {
        if (bestBid == null || bestAsk == null) {
            throw new IllegalArgumentException("Bid and ask prices must not be null");
        }
        if (bestBid.compareTo(BigDecimal.ZERO) <= 0 || bestAsk.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Prices must be positive");
        }
    }
}
