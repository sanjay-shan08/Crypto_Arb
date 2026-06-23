package com.arb.bitget.engine;

import com.arb.bitget.market.PriceCache;
import com.arb.bitget.model.PriceEntry;
import com.arb.bitget.model.RouteDirection;
import com.arb.bitget.model.Triangle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class RouteCalculatorTest {

    private RouteCalculator calculator;
    private PriceCache cache;
    private Triangle triangle;

    @BeforeEach
    void setUp() {
        calculator = new RouteCalculator(false);
        cache = new PriceCache();
        triangle = Triangle.parse("SOL/USDC/USDT");
    }

    private PriceEntry entry(String bid, String ask) {
        return new PriceEntry(
                new BigDecimal(bid),
                new BigDecimal(ask),
                BigDecimal.ONE,
                BigDecimal.ONE,
                System.currentTimeMillis(),
                null, null, null, null
        );
    }

    @Test
    void calculate_routeA_profitable() {
        // Route A: USDT -> SOL -> USDC -> USDT
        // Buy SOL with USDT (altBase ask)
        cache.update("SOLUSDT", entry("99", "100"));
        // Sell SOL for USDC (altInter bid)
        cache.update("SOLUSDC", entry("110", "111"));
        // Sell USDC for USDT (interBase bid)
        cache.update("USDCUSDT", entry("1.0", "1.01"));

        BigDecimal capital = new BigDecimal("1000");
        BigDecimal feeRate = new BigDecimal("0.001"); // 0.1%

        RouteResult result = calculator.calculate(triangle, cache, capital, feeRate);

        assertTrue(result.profitable());
        assertEquals(RouteDirection.ROUTE_A, result.direction());
        assertTrue(result.profitBps().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void calculate_routeB_profitable() {
        // Route B: USDT -> USDC -> SOL -> USDT
        // Buy USDC with USDT (interBase ask)
        cache.update("USDCUSDT", entry("0.99", "1.0"));
        // Buy SOL with USDC (altInter ask)
        cache.update("SOLUSDC", entry("99", "100"));
        // Sell SOL for USDT (altBase bid)
        cache.update("SOLUSDT", entry("110", "111"));

        BigDecimal capital = new BigDecimal("1000");
        BigDecimal feeRate = new BigDecimal("0.001"); // 0.1%

        RouteResult result = calculator.calculate(triangle, cache, capital, feeRate);

        assertTrue(result.profitable());
        assertEquals(RouteDirection.ROUTE_B, result.direction());
        assertTrue(result.profitBps().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void calculate_unprofitable_returnsNegativeProfit() {
        // Flat market, no spread, fees will make it unprofitable
        cache.update("SOLUSDT", entry("100", "100"));
        cache.update("SOLUSDC", entry("100", "100"));
        cache.update("USDCUSDT", entry("1.0", "1.0"));

        BigDecimal capital = new BigDecimal("1000");
        BigDecimal feeRate = new BigDecimal("0.001"); // 0.1%

        RouteResult result = calculator.calculate(triangle, cache, capital, feeRate);

        assertFalse(result.profitable());
        assertTrue(result.profitBps().compareTo(BigDecimal.ZERO) < 0);
    }

    @Test
    void calculate_missingPrices_returnsNoPrices() {
        // Only 2 out of 3 prices available
        cache.update("SOLUSDT", entry("99", "100"));
        cache.update("SOLUSDC", entry("110", "111"));

        BigDecimal capital = new BigDecimal("1000");
        BigDecimal feeRate = new BigDecimal("0.001");

        RouteResult result = calculator.calculate(triangle, cache, capital, feeRate);

        assertFalse(result.profitable());
        assertNull(result.direction());
        assertEquals(BigDecimal.ZERO, result.profitBps());
    }
}
