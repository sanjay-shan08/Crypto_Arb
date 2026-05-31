package com.arb.bitget.model;

import java.util.List;

/**
 * Immutable definition of a triangular arbitrage path.
 * Example: Triangle("SOLUSDT", "SOLBTC", "BTCUSDT") for SOL/BTC/USDT.
 *
 * <p>Thread safety: immutable record, safe for concurrent use.
 */
public record Triangle(
        String altUsdtPair,
        String altBtcPair,
        String btcUsdtPair
) {

    /**
     * Parses a human-readable triangle definition into a Triangle record.
     *
     * @param definition format: "ALT/BTC/USDT", e.g. "SOL/BTC/USDT"
     * @return Triangle with resolved pair names (e.g. SOLUSDT, SOLBTC, BTCUSDT)
     */
    public static Triangle parse(String definition) {
        String[] parts = definition.trim().split("/");
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "Triangle must have 3 parts: ALT/BTC/USDT, got: " + definition);
        }
        String alt = parts[0].trim().toUpperCase();
        String btc = parts[1].trim().toUpperCase();
        String usdt = parts[2].trim().toUpperCase();

        return new Triangle(
                alt + usdt,   // e.g. SOLUSDT
                alt + btc,    // e.g. SOLBTC
                btc + usdt    // e.g. BTCUSDT
        );
    }

    /**
     * Returns all three pair symbols for WebSocket subscription.
     */
    public List<String> allPairs() {
        return List.of(altUsdtPair, altBtcPair, btcUsdtPair);
    }
}
