package com.arb.bitget.executor;

import com.arb.bitget.model.OrderResult;
import com.arb.bitget.model.RouteDirection;
import com.arb.bitget.model.Side;
import com.arb.bitget.model.Signal;
import com.arb.bitget.model.Triangle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.arb.bitget.risk.CircuitBreaker;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Orchestrates the 3-leg execution sequence for a triangular arbitrage trade.
 * Handles the leg-by-leg dependency chain and invokes AbortHandler on failure.
 *
 * <p>Execution flow:
 * <ol>
 *   <li>Leg 1 → if FAIL → log, done (no position taken, safe)</li>
 *   <li>Leg 2 → if FAIL → AbortHandler.abort(leg1Result)</li>
 *   <li>Leg 3 → if FAIL → AbortHandler.abort(leg2Result)</li>
 *   <li>All SUCCESS → log profit, done</li>
 * </ol>
 *
 * <p>Thread safety: uses AtomicBoolean to track in-flight status.
 * Called from the risk-gate thread.
 */
public class TradeExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TradeExecutionService.class);

    private final OrderExecutor executor;
    private final AbortHandler abortHandler;
    private final CircuitBreaker circuitBreaker;
    private final BigDecimal feeRate;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicLong attemptedCount = new AtomicLong(0);
    private final AtomicLong successfulCount = new AtomicLong(0);
    private final AtomicLong failedCount = new AtomicLong(0);

    public TradeExecutionService(OrderExecutor executor, AbortHandler abortHandler, CircuitBreaker circuitBreaker, BigDecimal feeRate) {
        this.executor = executor;
        this.abortHandler = abortHandler;
        this.circuitBreaker = circuitBreaker;
        this.feeRate = feeRate;
    }

    /**
     * Returns true if a trade is currently being executed.
     * Used by RiskGate to prevent concurrent executions.
     */
    public boolean isInFlight() {
        return inFlight.get();
    }

    /**
     * Executes the full 3-leg triangle trade.
     */
    public void execute(Signal signal) {
        if (!inFlight.compareAndSet(false, true)) {
            log.warn("Trade already in-flight, skipping signal");
            return;
        }

        attemptedCount.incrementAndGet();
        try {
            executeTriangle(signal);
        } finally {
            inFlight.set(false);
        }
    }

    private void executeTriangle(Signal signal) {
        Triangle triangle = signal.triangle();
        RouteDirection direction = signal.direction();
        BigDecimal[] prices = signal.legPrices();
        BigDecimal[] quantities = signal.legQuantities();

        // Map legs to pairs and sides based on direction
        String leg1Pair;
        String leg2Pair;
        String leg3Pair;
        Side leg1Side;
        Side leg2Side;
        Side leg3Side;

        if (direction == RouteDirection.ROUTE_A) {
            // BASE → ALT → INTER → BASE
            leg1Pair = triangle.altBasePair();   // Buy ALT with BASE
            leg1Side = Side.BUY;
            leg2Pair = triangle.altInterPair();  // Sell ALT for INTER
            leg2Side = Side.SELL;
            leg3Pair = triangle.interBasePair(); // Sell INTER for BASE
            leg3Side = Side.SELL;
        } else {
            // BASE → INTER → ALT → BASE
            leg1Pair = triangle.interBasePair(); // Buy INTER with BASE
            leg1Side = Side.BUY;
            leg2Pair = triangle.altInterPair();  // Buy ALT with INTER
            leg2Side = Side.BUY;
            leg3Pair = triangle.altBasePair();   // Sell ALT for BASE
            leg3Side = Side.SELL;
        }

        long startTime = System.currentTimeMillis();

        // === Leg 1 ===
        log.info("Executing leg 1: pair={}, side={}, qty={}, price={}",
                leg1Pair, leg1Side, quantities[0], prices[0]);
        OrderResult leg1Result = executor.executeLeg(leg1Pair, leg1Side, quantities[0], prices[0]);

        if (!leg1Result.isSuccess()) {
            log.warn("Leg 1 FAILED — no position taken, safe. pair={}, status={}",
                    leg1Pair, leg1Result.status());
            failedCount.incrementAndGet();
            return;
        }
        log.info("Leg 1 FILLED: orderId={}, qty={}", leg1Result.orderId(), leg1Result.filledQuantity());

        // === Leg 2 ===
        log.info("Executing leg 2: pair={}, side={}, qty={}, price={}",
                leg2Pair, leg2Side, quantities[1], prices[1]);
        OrderResult leg2Result = executor.executeLeg(leg2Pair, leg2Side, quantities[1], prices[1]);

        if (!leg2Result.isSuccess()) {
            log.error("Leg 2 FAILED — triggering abort for leg 1. pair={}, status={}",
                    leg2Pair, leg2Result.status());
            abortHandler.abort(leg1Result, leg2Pair, leg2Side);
            failedCount.incrementAndGet();
            if (circuitBreaker != null) circuitBreaker.onAbort();
            return;
        }
        log.info("Leg 2 FILLED: orderId={}, qty={}", leg2Result.orderId(), leg2Result.filledQuantity());

        // === Leg 3 ===
        log.info("Executing leg 3: pair={}, side={}, qty={}, price={}",
                leg3Pair, leg3Side, quantities[2], prices[2]);
        OrderResult leg3Result = executor.executeLeg(leg3Pair, leg3Side, quantities[2], prices[2]);

        if (!leg3Result.isSuccess()) {
            log.error("Leg 3 FAILED — triggering abort for leg 2. pair={}, status={}",
                    leg3Pair, leg3Result.status());
            abortHandler.abort(leg2Result, leg3Pair, leg3Side);
            failedCount.incrementAndGet();
            if (circuitBreaker != null) circuitBreaker.onAbort();
            return;
        }
        log.info("Leg 3 FILLED: orderId={}, qty={}", leg3Result.orderId(), leg3Result.filledQuantity());

        long elapsed = System.currentTimeMillis() - startTime;
        long totalSuccess = successfulCount.incrementAndGet();

        // Calculate estimated net realized P&L
        BigDecimal leg1BaseSpent = leg1Result.filledQuantity().multiply(leg1Result.filledPrice());
        BigDecimal leg3BaseReceived = leg3Result.filledQuantity().multiply(leg3Result.filledPrice());
        BigDecimal grossProfit = leg3BaseReceived.subtract(leg1BaseSpent);
        BigDecimal estimatedFee = leg1BaseSpent.multiply(feeRate).multiply(BigDecimal.valueOf(3));
        BigDecimal netProfit = grossProfit.subtract(estimatedFee);

        if (circuitBreaker != null) {
            circuitBreaker.onTradeSuccess(netProfit);
        }

        log.info("\u001B[32m\u001B[1m=== Triangle execution COMPLETE (Total Successful Arbitrages: {}) === direction={}, netProfit={} USDT, elapsed={}ms\u001B[0m",
                totalSuccess, direction, netProfit, elapsed);
    }

    public long getAttemptedCount() {
        return attemptedCount.get();
    }

    public long getSuccessfulCount() {
        return successfulCount.get();
    }

    public long getFailedCount() {
        return failedCount.get();
    }
}
