package com.arb.bitget.executor;

import com.arb.bitget.model.OrderResult;
import com.arb.bitget.model.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simulated executor for paper trading. No real API calls.
 * Logs trades to console and returns synthetic FILLED results.
 * Simulates ~200ms network latency per leg for realistic timing.
 *
 * <p>Thread safety: uses AtomicLong for orderId counter.
 * Called sequentially from the trade-executor thread.
 */
public class PaperExecutor implements OrderExecutor {

    private static final Logger log = LoggerFactory.getLogger(PaperExecutor.class);
    private static final long SIMULATED_LATENCY_MS = 200;

    private final AtomicLong orderIdCounter = new AtomicLong(1);

    @Override
    public OrderResult executeLeg(String pair, Side side, BigDecimal quantity, BigDecimal price) {
        // Simulate network latency
        try {
            Thread.sleep(SIMULATED_LATENCY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String orderId = "PAPER-" + orderIdCounter.getAndIncrement();
        BigDecimal fillPrice = price != null ? price : BigDecimal.ZERO;

        log.info("[PAPER] Trade executed: pair={}, side={}, qty={}, price={}, orderId={}",
                pair, side, quantity, fillPrice, orderId);

        return new OrderResult(orderId, orderId, OrderResult.Status.FILLED,
                quantity, fillPrice, pair, side);
    }
}
