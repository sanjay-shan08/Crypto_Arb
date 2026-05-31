package com.arb.bitget.model;

import java.math.BigDecimal;

/**
 * Trading pair metadata loaded from Bitget's symbol info endpoint.
 * Used for order size validation and quantity rounding.
 *
 * <p>Thread safety: immutable record, safe for concurrent use.
 */
public record TradingPair(
        String symbol,
        int quantityPrecision,
        int pricePrecision,
        BigDecimal minTradeUsdt
) {
}
