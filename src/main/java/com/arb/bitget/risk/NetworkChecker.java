package com.arb.bitget.risk;

import com.arb.bitget.executor.BitgetApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Measures REST API round-trip latency to Bitget servers.
 *
 * <p><b>Preflight (startup):</b> Sends N pings, calculates average.
 * If avg > threshold → refuses to start.
 *
 * <p><b>Runtime monitoring:</b> Every 30s, sends N pings.
 * If latency exceeds threshold → pauses engine.
 * If latency recovers → resumes engine.
 *
 * <p>Uses the same OkHttpClient as the executor so measurements
 * reflect real trading conditions (connection pooling included).
 *
 * <p>Thread safety: runs on its own scheduled thread ("network-checker").
 */
public class NetworkChecker {

    private static final Logger log = LoggerFactory.getLogger(NetworkChecker.class);

    private final BitgetApiClient apiClient;
    private final long maxLatencyMs;
    private final int preflightSamples;
    private final int runtimeSamples;
    private final long checkIntervalS;
    private final Runnable onLatencyHigh;
    private final Runnable onLatencyRecovered;

    private ScheduledExecutorService scheduler;
    private volatile boolean enginePaused = false;

    public NetworkChecker(BitgetApiClient apiClient, long maxLatencyMs,
                          int preflightSamples, int runtimeSamples, long checkIntervalS,
                          Runnable onLatencyHigh, Runnable onLatencyRecovered) {
        this.apiClient = apiClient;
        this.maxLatencyMs = maxLatencyMs;
        this.preflightSamples = preflightSamples;
        this.runtimeSamples = runtimeSamples;
        this.checkIntervalS = checkIntervalS;
        this.onLatencyHigh = onLatencyHigh;
        this.onLatencyRecovered = onLatencyRecovered;
    }

    /**
     * Blocking preflight check. Sends multiple pings and validates latency.
     *
     * @return true if latency is acceptable, false to abort startup
     */
    public boolean preflight() {
        log.info("NetworkChecker: preflight latency check ({} samples, threshold={}ms)",
                preflightSamples, maxLatencyMs);
        long avgLatency = measureLatency(preflightSamples);

        if (avgLatency < 0) {
            log.error("CRITICAL: Preflight FAILED — cannot reach Bitget API");
            return false;
        }

        if (avgLatency > maxLatencyMs) {
            log.error("CRITICAL: Preflight FAILED — avg latency {}ms exceeds threshold {}ms",
                    avgLatency, maxLatencyMs);
            return false;
        }

        log.info("NetworkChecker: preflight PASSED — avg latency {}ms (threshold={}ms)",
                avgLatency, maxLatencyMs);
        return true;
    }

    /**
     * Starts the periodic runtime latency monitoring.
     */
    public void startMonitoring() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "network-checker");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::runtimeCheck, checkIntervalS, checkIntervalS, TimeUnit.SECONDS);
        log.info("NetworkChecker: runtime monitoring started (every {}s)", checkIntervalS);
    }

    private void runtimeCheck() {
        long avgLatency = measureLatency(runtimeSamples);

        if (avgLatency < 0) {
            log.error("CRITICAL: Cannot reach Bitget API — pausing engine");
            if (!enginePaused) {
                onLatencyHigh.run();
                enginePaused = true;
            }
            return;
        }

        if (avgLatency > maxLatencyMs) {
            log.warn("\u001B[31mNetwork latency high: {}ms (threshold={}ms) — pausing engine\u001B[0m",
                    avgLatency, maxLatencyMs);
            if (!enginePaused) {
                onLatencyHigh.run();
                enginePaused = true;
            }
        } else {
            if (enginePaused) {
                log.info("Network latency recovered: {}ms — resuming engine", avgLatency);
                onLatencyRecovered.run();
                enginePaused = false;
            } else {
                log.debug("\u001B[32mNetwork latency OK: {}ms\u001B[0m", avgLatency);
            }
        }
    }

    private long measureLatency(int samples) {
        long total = 0;
        int successful = 0;

        for (int i = 0; i < samples; i++) {
            try {
                long rtt = apiClient.getServerTime();
                total += rtt;
                successful++;
                log.debug("\u001B[32mPing #{}: {}ms\u001B[0m", i + 1, rtt);
            } catch (Exception e) {
                log.warn("\u001B[31mPing #{} failed: {}\u001B[0m", i + 1, e.getMessage());
            }
        }

        if (successful == 0) {
            return -1;
        }
        return total / successful;
    }

    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            log.info("NetworkChecker stopped");
        }
    }
}
