package com.arb.bitget.model;

import java.math.BigDecimal;

/**
 * Result of a single leg order execution.
 * Returned by OrderExecutor implementations.
 *
 * <p>Thread safety: immutable record, safe for concurrent use.
 */
public record OrderResult(
        String orderId,
        String clientOid,
        Status status,
        BigDecimal filledQuantity,
        BigDecimal filledPrice,
        String pair,
        Side side
) {

    public enum Status {
        FILLED,
        PARTIALLY_FILLED,
        FAILED,
        REJECTED
    }

    /**
     * Returns true if the order filled (fully or partially).
     */
    public boolean isSuccess() {
        return status == Status.FILLED || status == Status.PARTIALLY_FILLED;
    }
}
