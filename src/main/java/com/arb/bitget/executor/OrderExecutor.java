package com.arb.bitget.executor;

import com.arb.bitget.model.OrderResult;
import com.arb.bitget.model.Side;

import java.math.BigDecimal;

/**
 * Interface for executing a single trade leg.
 * Implementations: PaperExecutor, SandboxExecutor, LiveExecutor.
 *
 * <p>All implementations are drop-in replacements — same interface,
 * same method signature. The active implementation is selected once
 * at startup in Main.java based on the --mode flag.
 *
 * <p>Thread safety: implementations must be safe for sequential calls
 * from the trade-executor thread.
 */
public interface OrderExecutor {

    /**
     * Executes a single trade leg.
     *
     * @param pair     trading pair symbol (e.g. "SOLUSDT")
     * @param side     BUY or SELL
     * @param quantity order quantity in base currency
     * @param price    limit price, or null for market orders
     * @return the result of the order execution
     */
    OrderResult executeLeg(String pair, Side side, BigDecimal quantity, BigDecimal price);
}
