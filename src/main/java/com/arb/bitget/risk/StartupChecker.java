package com.arb.bitget.risk;

import com.arb.bitget.executor.BitgetApiClient;
import com.arb.bitget.model.Triangle;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs once at startup, BEFORE the engine starts.
 * Cancels any stale orders left over from a previous crash.
 *
 * <p>Thread safety: runs on the main thread during startup.
 */
public class StartupChecker {

    private static final Logger log = LoggerFactory.getLogger(StartupChecker.class);

    private final BitgetApiClient apiClient;
    private final List<Triangle> triangles;

    public StartupChecker(BitgetApiClient apiClient, List<Triangle> triangles) {
        this.apiClient = apiClient;
        this.triangles = triangles;
    }

    /**
     * Queries all configured pairs for open orders and cancels them.
     * Blocks until complete.
     */
    public void run() throws Exception {
        log.info("StartupChecker: validating API credentials...");
        apiClient.testCredentials();

        log.info("StartupChecker: scanning for stale orders...");

        // Collect unique pairs from all triangles
        Set<String> pairs = new HashSet<>();
        for (Triangle triangle : triangles) {
            pairs.addAll(triangle.allPairs());
        }

        int cancelledCount = 0;
        for (String pair : pairs) {
            try {
                List<JsonObject> orders = apiClient.getUnfilledOrders(pair);
                for (JsonObject order : orders) {
                    String orderId = order.has("orderId")
                            ? order.get("orderId").getAsString() : "unknown";
                    boolean cancelled = apiClient.cancelOrder(pair, orderId);
                    if (cancelled) {
                        cancelledCount++;
                        log.warn("Cancelled stale order: orderId={}, pair={}", orderId, pair);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to check orders for pair={}: {}", pair, e.getMessage());
            }
        }

        if (cancelledCount > 0) {
            log.warn("StartupChecker: cancelled {} stale orders", cancelledCount);
        } else {
            log.info("StartupChecker: no stale orders found — clean startup");
        }
    }
}
