package com.arb.bitget.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Handles WebSocket reconnection with exponential backoff.
 * Invoked by BitgetWebSocketClient on disconnect or failure.
 *
 * <p>Backoff formula: min(2^attempt × 100ms, 30s).
 * Resets attempt counter on successful reconnection.
 *
 * <p>Thread safety: scheduleReconnect may be called from the WebSocket thread.
 * The reconnect itself runs on a dedicated scheduler thread.
 */
public class ReconnectHandler {

    private static final Logger log = LoggerFactory.getLogger(ReconnectHandler.class);
    private static final long MAX_DELAY_MS = 30_000;
    private static final long BASE_DELAY_MS = 100;

    private volatile int attempts = 0;
    private final ScheduledExecutorService scheduler;

    public ReconnectHandler() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-reconnect");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Schedules a reconnection attempt with exponential backoff.
     *
     * @param client the WebSocket client to reconnect
     */
    public void scheduleReconnect(BitgetWebSocketClient client) {
        long delay = Math.min((long) Math.pow(2, attempts) * BASE_DELAY_MS, MAX_DELAY_MS);
        attempts++;
        log.warn("Scheduling WebSocket reconnection attempt #{} in {}ms", attempts, delay);
        scheduler.schedule(() -> {
            try {
                client.connect();
            } catch (Exception e) {
                log.error("Reconnection attempt #{} failed", attempts, e);
                scheduleReconnect(client);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * Resets the backoff counter. Called on successful reconnection.
     */
    public void resetAttempts() {
        attempts = 0;
    }

    public void stop() {
        if (!scheduler.isShutdown()) {
            scheduler.shutdownNow();
            log.info("ReconnectHandler stopped");
        }
    }
}
