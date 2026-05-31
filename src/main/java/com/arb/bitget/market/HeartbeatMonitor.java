package com.arb.bitget.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Monitors PriceCache for stale data and kills the engine if no update
 * arrives within the configured timeout.
 *
 * <p>Checks every 1 second. If the gap since last price update exceeds
 * {@code timeoutMs}, invokes the kill callback (typically ArbitrageEngine.kill()).
 *
 * <p>Thread safety: runs on its own scheduled thread. The kill callback
 * may be invoked from the heartbeat thread.
 */
public class HeartbeatMonitor {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatMonitor.class);

    private final PriceCache priceCache;
    private final long timeoutMs;
    private final Runnable engineKillCallback;
    private ScheduledExecutorService scheduler;

    public HeartbeatMonitor(PriceCache priceCache, long timeoutMs, Runnable engineKillCallback) {
        this.priceCache = priceCache;
        this.timeoutMs = timeoutMs;
        this.engineKillCallback = engineKillCallback;
    }

    /**
     * Starts the heartbeat check loop (every 1 second).
     */
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-monitor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::check, 1, 1, TimeUnit.SECONDS);
        log.info("HeartbeatMonitor started: timeout={}ms", timeoutMs);
    }

    private void check() {
        long lastUpdate = priceCache.getLastUpdateTime();
        if (lastUpdate == 0) {
            // No prices received yet — skip
            return;
        }
        long gap = System.currentTimeMillis() - lastUpdate;
        if (gap > timeoutMs) {
            log.error("CRITICAL: No price update for {}ms (threshold={}ms) — killing engine", gap, timeoutMs);
            engineKillCallback.run();
        }
    }

    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            log.info("HeartbeatMonitor stopped");
        }
    }
}
