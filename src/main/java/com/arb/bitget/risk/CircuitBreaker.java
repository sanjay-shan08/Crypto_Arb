package com.arb.bitget.risk;

import com.arb.bitget.config.AppConfig;
import com.arb.bitget.engine.ArbitrageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Advanced safety net that monitors trade execution results.
 * 
 * <p>Features:
 * <ol>
 *   <li><b>Kill Switch</b>: Halts the engine if consecutive aborts exceed the threshold.</li>
 *   <li><b>Daily Loss Limit</b>: Halts the engine if cumulative daily net P&L drops below the allowed loss threshold.</li>
 * </ol>
 * 
 * <p>Thread safety: Uses AtomicReference and AtomicInteger, safe to be called from executor threads.
 */
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private final BigDecimal maxDailyLossUsdt;
    private final int maxConsecutiveAborts;
    private final ArbitrageEngine engine;

    private final AtomicReference<BigDecimal> netDailyPnl = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicInteger consecutiveAborts = new AtomicInteger(0);

    public CircuitBreaker(AppConfig config, ArbitrageEngine engine) {
        this.maxDailyLossUsdt = config.getMaxDailyLossUsdt();
        this.maxConsecutiveAborts = config.getMaxConsecutiveAborts();
        this.engine = engine;
    }

    /**
     * Called by TradeExecutionService whenever the AbortHandler is triggered.
     */
    public void onAbort() {
        int aborts = consecutiveAborts.incrementAndGet();
        log.warn("CircuitBreaker: Consecutive aborts increased to {}", aborts);
        
        if (aborts >= maxConsecutiveAborts) {
            log.error("\u001B[41m\u001B[37m\u001B[1mCRITICAL: Max consecutive aborts ({}) reached! Triggering Kill Switch.\u001B[0m", maxConsecutiveAborts);
            engine.kill();
        }
    }

    /**
     * Called by TradeExecutionService upon successful completion of a 3-leg arbitrage.
     * 
     * @param realizedProfitUsdt the net realized profit (or loss) in USDT.
     */
    public void onTradeSuccess(BigDecimal realizedProfitUsdt) {
        consecutiveAborts.set(0); // reset consecutive aborts on a clean execution
        
        BigDecimal currentPnl = netDailyPnl.accumulateAndGet(realizedProfitUsdt, BigDecimal::add);
        
        log.info("CircuitBreaker: Trade finalized. Realized PnL: {} USDT. Cumulative Daily PnL: {} USDT", 
                realizedProfitUsdt, currentPnl);

        // If PnL <= -maxDailyLossUsdt
        if (currentPnl.compareTo(maxDailyLossUsdt.negate()) <= 0) {
            log.error("\u001B[41m\u001B[37m\u001B[1mCRITICAL: Daily loss limit ({} USDT) breached! Current PnL: {}. Triggering Kill Switch.\u001B[0m", 
                    maxDailyLossUsdt, currentPnl);
            engine.kill();
        }
    }

    public BigDecimal getNetDailyPnl() {
        return netDailyPnl.get();
    }

    public int getConsecutiveAborts() {
        return consecutiveAborts.get();
    }
}
