package com.arb.bitget.model;

import java.math.BigDecimal;

/**
 * An arbitrage signal produced by the engine when a profitable route is detected.
 * Passed through SignalQueue → RiskGate → TradeExecutionService.
 *
 * <p>Thread safety: immutable record, safe for concurrent use.
 * The {@link #incrementRetry()} method returns a new instance.
 */
public record Signal(
        Triangle triangle,
        RouteDirection direction,
        BigDecimal expectedProfitBps,
        BigDecimal[] legPrices,
        BigDecimal[] legQuantities,
        BigDecimal capitalUsdt,
        long detectedAt,
        int retryCount
) {

    /**
     * Returns a new Signal with retryCount incremented by 1.
     * Used by RiskGate when re-queuing a rejected signal.
     */
    public Signal incrementRetry() {
        return new Signal(
                triangle, direction, expectedProfitBps,
                legPrices, legQuantities, capitalUsdt,
                detectedAt, retryCount + 1
        );
    }
}
