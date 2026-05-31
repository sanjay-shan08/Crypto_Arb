package com.arb.bitget.model;

/**
 * Direction of a triangular arbitrage route.
 *
 * <p>Thread safety: immutable enum, safe for concurrent use.
 */
public enum RouteDirection {

    /** USDT → ALT → BTC → USDT */
    ROUTE_A("USDT → ALT → BTC → USDT"),

    /** USDT → BTC → ALT → USDT */
    ROUTE_B("USDT → BTC → ALT → USDT");

    private final String description;

    RouteDirection(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
