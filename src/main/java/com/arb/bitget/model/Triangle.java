package com.arb.bitget.model;

import java.util.List;

/**
 * Immutable definition of a triangular arbitrage path.
 * Supports any intermediary coin — BTC, USDC, ETH, etc.
 *
 * <p>Format: ALT / INTER / BASE (e.g. "SOL/USDC/USDT", "TRX/ETH/USDT", "SOL/BTC/USDT").
 * <ul>
 *   <li>altBasePair  — ALT quoted in BASE  (e.g. SOLUSDT)</li>
 *   <li>altInterPair — ALT quoted in INTER (e.g. SOLUSDC)</li>
 *   <li>interBasePair — INTER quoted in BASE (e.g. USDCUSDT)</li>
 * </ul>
 *
 * <p>Thread safety: immutable record, safe for concurrent use.
 */
public record Triangle(
        String altCoin,
        String interCoin,
        String baseCoin,
        String altBasePair,
        String altInterPair,
        String interBasePair
) {

    /**
     * Parses a human-readable triangle definition into a Triangle record.
     *
     * @param definition format: "ALT/INTER/BASE", e.g. "SOL/USDC/USDT" or "SOL/BTC/USDT"
     * @return Triangle with resolved pair names (e.g. SOLUSDT, SOLUSDC, USDCUSDT)
     */
    public static Triangle parse(String definition) {
        String[] parts = definition.trim().split("/");
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "Triangle must have 3 parts: ALT/INTER/BASE, got: " + definition);
        }
        String alt = parts[0].trim().toUpperCase();
        String inter = parts[1].trim().toUpperCase();
        String base = parts[2].trim().toUpperCase();

        return new Triangle(
                alt,
                inter,
                base,
                alt + base,    // e.g. SOLUSDT
                alt + inter,   // e.g. SOLUSDC
                inter + base   // e.g. USDCUSDT
        );
    }

    /**
     * Returns all three pair symbols for WebSocket subscription.
     */
    public List<String> allPairs() {
        return List.of(altBasePair, altInterPair, interBasePair);
    }
}
