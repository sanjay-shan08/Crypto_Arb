package com.arb.bitget.model;

/**
 * Order side for Bitget API.
 *
 * <p>Thread safety: immutable enum, safe for concurrent use.
 */
public enum Side {

    BUY("buy"),
    SELL("sell");

    private final String apiValue;

    Side(String apiValue) {
        this.apiValue = apiValue;
    }

    /**
     * Returns the lowercase string expected by the Bitget REST API.
     */
    public String toApiString() {
        return apiValue;
    }
}
