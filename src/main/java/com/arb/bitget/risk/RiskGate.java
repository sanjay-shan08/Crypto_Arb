package com.arb.bitget.risk;

import com.arb.bitget.config.AppConfig;
import com.arb.bitget.engine.SignalQueue;
import com.arb.bitget.executor.TradeExecutionService;
import com.arb.bitget.model.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gate that validates signals before allowing execution.
 * Consumes from SignalQueue, applies safety checks, and either
 * forwards approved signals to TradeExecutionService or re-queues them.
 *
 * <p>Checks (in order):
 * <ol>
 *   <li>Staleness — signal must be less than 500ms old</li>
 *   <li>Minimum profit — must meet configured threshold</li>
 *   <li>Position size — must not exceed max position</li>
 *   <li>Concurrent execution — no other trade in-flight</li>
 * </ol>
 *
 * <p>Thread safety: runs on its own dedicated thread ("risk-gate").
 */
public class RiskGate {

    private static final Logger log = LoggerFactory.getLogger(RiskGate.class);
    private static final long MAX_SIGNAL_AGE_MS = 500;
    private static final int MAX_RETRIES = 3;

    private final SignalQueue signalQueue;
    private final TradeExecutionService executionService;
    private final BigDecimal minProfitBps;
    private final BigDecimal maxPositionUsdt;
    private ExecutorService consumerThread;
    private volatile boolean running = false;

    public RiskGate(SignalQueue signalQueue, TradeExecutionService executionService, AppConfig config) {
        this.signalQueue = signalQueue;
        this.executionService = executionService;
        this.minProfitBps = config.getMinProfitBps();
        this.maxPositionUsdt = config.getMaxPositionUsdt();
    }

    /**
     * Starts the signal consumer loop on a dedicated thread.
     */
    public void start() {
        running = true;
        consumerThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "risk-gate");
            t.setDaemon(true);
            return t;
        });
        consumerThread.submit(this::consumeLoop);
        log.info("RiskGate started");
    }

    private void consumeLoop() {
        while (running) {
            try {
                Signal signal = signalQueue.take();
                evaluate(signal);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in RiskGate consumer loop", e);
            }
        }
    }

    private void evaluate(Signal signal) {
        // Check 1: Staleness
        long age = System.currentTimeMillis() - signal.detectedAt();
        if (age > MAX_SIGNAL_AGE_MS) {
            log.debug("Signal REJECTED (stale): age={}ms, max={}ms, triangle={}",
                    age, MAX_SIGNAL_AGE_MS, signal.triangle().altBasePair());
            requeue(signal);
            return;
        }

        // Check 2: Minimum profit
        if (signal.expectedProfitBps().compareTo(minProfitBps) < 0) {
            log.debug("Signal REJECTED (low profit): profit={}bps, min={}bps",
                    signal.expectedProfitBps(), minProfitBps);
            requeue(signal);
            return;
        }

        // Check 3: Position size
        if (signal.capitalUsdt().compareTo(maxPositionUsdt) > 0) {
            log.debug("Signal REJECTED (position too large): capital={}, max={}",
                    signal.capitalUsdt(), maxPositionUsdt);
            requeue(signal);
            return;
        }

        // Check 4: Concurrent execution
        if (executionService.isInFlight()) {
            log.debug("Signal REJECTED (trade in-flight), triangle={}",
                    signal.triangle().altBasePair());
            requeue(signal);
            return;
        }

        // All checks passed — execute
        log.info("Signal ACCEPTED: triangle={}, direction={}, profit={}bps",
                signal.triangle().altBasePair(), signal.direction(), signal.expectedProfitBps());

        executionService.execute(signal);
    }

    private void requeue(Signal signal) {
        if (signal.retryCount() >= MAX_RETRIES) {
            log.debug("Signal DROPPED (max retries={}): triangle={}",
                    signal.retryCount(), signal.triangle().altBasePair());
            return;
        }
        signalQueue.put(signal.incrementRetry());
    }

    public void stop() {
        running = false;
        if (consumerThread != null && !consumerThread.isShutdown()) {
            consumerThread.shutdownNow();
        }
        log.info("RiskGate stopped");
    }
}
