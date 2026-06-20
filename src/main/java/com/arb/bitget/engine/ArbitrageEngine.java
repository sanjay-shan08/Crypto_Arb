package com.arb.bitget.engine;

import com.arb.bitget.config.AppConfig;
import com.arb.bitget.market.PriceCache;
import com.arb.bitget.model.Signal;
import com.arb.bitget.model.Triangle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Fires every {@code tickMs} milliseconds, reads prices from PriceCache,
 * calculates arbitrage routes for all configured triangles, and enqueues
 * profitable signals to SignalQueue.
 *
 * <p>Thread safety: all public methods are called from the main thread
 * or shutdown hook. The tick() method runs on the engine scheduler thread.
 * The kill() method may be called from the HeartbeatMonitor thread.
 */
public class ArbitrageEngine {

    private static final Logger log = LoggerFactory.getLogger(ArbitrageEngine.class);

    private final PriceCache priceCache;
    private final RouteCalculator calculator;
    private final SignalQueue signalQueue;
    private final List<Triangle> triangles;
    private final BigDecimal minProfitBps;
    private final BigDecimal capitalUsdt;
    private final BigDecimal feeRate;
    private final long tickMs;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tickTask;
    private volatile boolean running = false;
    private volatile boolean paused = false;

    public ArbitrageEngine(PriceCache priceCache, RouteCalculator calculator,
                           SignalQueue signalQueue, AppConfig config) {
        this.priceCache = priceCache;
        this.calculator = calculator;
        this.signalQueue = signalQueue;
        this.triangles = config.getTriangles();
        this.minProfitBps = config.getMinProfitBps();
        this.capitalUsdt = config.getMaxPositionUsdt();
        this.feeRate = config.getFeeRate();
        this.tickMs = config.getEngineTickMs();
    }

    /**
     * Starts the engine tick loop.
     */
    public void start() {
        if (running) {
            log.warn("Engine already running");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "arb-engine");
            t.setDaemon(true);
            return t;
        });
        tickTask = scheduler.scheduleAtFixedRate(this::tick, 0, tickMs, TimeUnit.MILLISECONDS);
        running = true;
        log.info("ArbitrageEngine started: tickMs={}, triangles={}, minProfitBps={}",
                tickMs, triangles.size(), minProfitBps);
    }

    private void tick() {
        if (paused) {
            return;
        }
        try {
            for (Triangle triangle : triangles) {
                RouteResult result = calculator.calculate(triangle, priceCache, capitalUsdt, feeRate);
                if (result.profitable() && result.profitBps().compareTo(minProfitBps) >= 0) {
                    Signal signal = new Signal(
                            triangle,
                            result.direction(),
                            result.profitBps(),
                            result.legPrices(),
                            result.legQuantities(),
                            capitalUsdt,
                            System.currentTimeMillis(),
                            0
                    );
                    signalQueue.put(signal);
                    log.info("\u001B[32m\u001B[1m[PROFITABLE SIGNAL] triangle={}, direction={}, profit={}bps\u001B[0m",
                            triangle.altBasePair(), result.direction(), result.profitBps());
                }
            }
        } catch (Exception e) {
            log.error("Error in engine tick", e);
        }
    }

    /**
     * Gracefully stops the engine (allows current tick to finish).
     */
    public void stop() {
        running = false;
        paused = false;
        if (tickTask != null) {
            tickTask.cancel(false);
        }
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
        log.info("ArbitrageEngine stopped");
    }

    /**
     * Immediately kills the engine. Called by HeartbeatMonitor on stale data.
     */
    public void kill() {
        running = false;
        paused = false;
        if (tickTask != null) {
            tickTask.cancel(true);
        }
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        log.error("CRITICAL: ArbitrageEngine killed");
    }

    /**
     * Temporarily pauses the engine. Called by NetworkChecker on high latency.
     */
    public void pause() {
        if (!paused) {
            paused = true;
            log.warn("ArbitrageEngine paused");
        }
    }

    /**
     * Resumes the engine after a pause. Called by NetworkChecker on recovery.
     */
    public void resume() {
        if (paused) {
            paused = false;
            log.info("ArbitrageEngine resumed");
        }
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isPaused() {
        return paused;
    }
}
