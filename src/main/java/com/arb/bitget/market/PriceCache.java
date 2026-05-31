package com.arb.bitget.market;

import com.arb.bitget.model.PriceEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe price store backed by ConcurrentHashMap.
 * Written to by the WebSocket thread, read by the Engine thread.
 *
 * <p>Thread safety: ConcurrentHashMap for price data,
 * volatile for lastUpdateTime. Safe for concurrent read/write.
 */
public class PriceCache {

    private static final Logger log = LoggerFactory.getLogger(PriceCache.class);

    private final ConcurrentHashMap<String, PriceEntry> prices = new ConcurrentHashMap<>();
    private volatile long lastUpdateTime = 0;

    /**
     * Updates the price entry for a trading pair.
     * Called from the WebSocket read thread.
     *
     * @param pair  trading pair symbol (e.g. "BTCUSDT")
     * @param entry immutable price snapshot
     */
    public void update(String pair, PriceEntry entry) {
        prices.put(pair, entry);
        lastUpdateTime = System.currentTimeMillis();
        log.debug("Price updated: pair={}, bid={}, ask={}", pair, entry.bestBid(), entry.bestAsk());
    }

    /**
     * Returns the latest price snapshot for a pair, or null if not yet received.
     */
    public PriceEntry getPrice(String pair) {
        return prices.get(pair);
    }

    /**
     * Returns the epoch millis of the most recent price update.
     * Used by HeartbeatMonitor to detect stale data.
     */
    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    /**
     * Returns the number of pairs currently tracked.
     */
    public int size() {
        return prices.size();
    }
}
