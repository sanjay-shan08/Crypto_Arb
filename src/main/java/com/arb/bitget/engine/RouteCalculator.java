package com.arb.bitget.engine;

import com.arb.bitget.market.PriceCache;
import com.arb.bitget.model.PriceEntry;
import com.arb.bitget.model.RouteDirection;
import com.arb.bitget.model.Triangle;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure-function calculator for triangular arbitrage routes.
 * No state, no side effects — all math uses BigDecimal with scale 8.
 *
 * <p>Evaluates both Route A (USDT→ALT→BTC→USDT) and Route B
 * (USDT→BTC→ALT→USDT), returns whichever is more profitable.
 *
 * <p>Thread safety: stateless, safe for concurrent use.
 */
public class RouteCalculator {

    private static final int SCALE = 8;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal BPS_MULTIPLIER = new BigDecimal("10000");
    private static final BigDecimal ONE = BigDecimal.ONE;

    /**
     * Calculates the best arbitrage route for a triangle.
     *
     * @param triangle    the three pairs defining the triangle
     * @param cache       live price data
     * @param capitalUsdt the USDT capital to deploy
     * @param feeRate     per-trade fee rate (e.g. 0.001 for 0.1%)
     * @return the better of the two routes, or noPrices() if data is missing
     */
    public RouteResult calculate(Triangle triangle, PriceCache cache,
                                 BigDecimal capitalUsdt, BigDecimal feeRate) {
        PriceEntry altUsdt = cache.getPrice(triangle.altUsdtPair());
        PriceEntry altBtc = cache.getPrice(triangle.altBtcPair());
        PriceEntry btcUsdt = cache.getPrice(triangle.btcUsdtPair());

        if (altUsdt == null || altBtc == null || btcUsdt == null) {
            return RouteResult.noPrices();
        }

        BigDecimal feeMultiplier = ONE.subtract(feeRate);

        RouteResult routeA = calculateRouteA(capitalUsdt, feeMultiplier, altUsdt, altBtc, btcUsdt);
        RouteResult routeB = calculateRouteB(capitalUsdt, feeMultiplier, altUsdt, altBtc, btcUsdt);

        // Return the more profitable route
        if (routeA.profitBps().compareTo(routeB.profitBps()) >= 0) {
            return routeA;
        }
        return routeB;
    }

    /**
     * Route A: USDT → ALT → BTC → USDT
     *
     * Leg 1: Buy ALT with USDT (ask price on ALTUSDT)
     * Leg 2: Sell ALT for BTC  (bid price on ALTBTC)
     * Leg 3: Sell BTC for USDT (bid price on BTCUSDT)
     */
    private RouteResult calculateRouteA(BigDecimal capital, BigDecimal feeMultiplier,
                                        PriceEntry altUsdt, PriceEntry altBtc, PriceEntry btcUsdt) {
        BigDecimal leg1Price = altUsdt.bestAsk();
        BigDecimal leg2Price = altBtc.bestBid();
        BigDecimal leg3Price = btcUsdt.bestBid();

        // Leg 1: Buy ALT with USDT
        BigDecimal leg1Qty = capital.divide(leg1Price, SCALE, ROUNDING);
        BigDecimal altReceived = leg1Qty.multiply(feeMultiplier).setScale(SCALE, ROUNDING);

        // Leg 2: Sell ALT for BTC
        BigDecimal leg2Qty = altReceived;
        BigDecimal btcBeforeFee = leg2Qty.multiply(leg2Price).setScale(SCALE, ROUNDING);
        BigDecimal btcReceived = btcBeforeFee.multiply(feeMultiplier).setScale(SCALE, ROUNDING);

        // Leg 3: Sell BTC for USDT
        BigDecimal leg3Qty = btcReceived;
        BigDecimal usdtBeforeFee = leg3Qty.multiply(leg3Price).setScale(SCALE, ROUNDING);
        BigDecimal usdtReceived = usdtBeforeFee.multiply(feeMultiplier).setScale(SCALE, ROUNDING);

        BigDecimal profit = usdtReceived.subtract(capital);
        BigDecimal profitBps = profit.divide(capital, SCALE, ROUNDING)
                .multiply(BPS_MULTIPLIER).setScale(2, ROUNDING);

        return new RouteResult(
                RouteDirection.ROUTE_A,
                profitBps,
                new BigDecimal[]{leg1Price, leg2Price, leg3Price},
                new BigDecimal[]{leg1Qty, leg2Qty, leg3Qty},
                profitBps.compareTo(BigDecimal.ZERO) > 0
        );
    }

    /**
     * Route B: USDT → BTC → ALT → USDT
     *
     * Leg 1: Buy BTC with USDT (ask price on BTCUSDT)
     * Leg 2: Buy ALT with BTC  (ask price on ALTBTC)
     * Leg 3: Sell ALT for USDT (bid price on ALTUSDT)
     */
    private RouteResult calculateRouteB(BigDecimal capital, BigDecimal feeMultiplier,
                                        PriceEntry altUsdt, PriceEntry altBtc, PriceEntry btcUsdt) {
        BigDecimal leg1Price = btcUsdt.bestAsk();
        BigDecimal leg2Price = altBtc.bestAsk();
        BigDecimal leg3Price = altUsdt.bestBid();

        // Leg 1: Buy BTC with USDT
        BigDecimal leg1Qty = capital.divide(leg1Price, SCALE, ROUNDING);
        BigDecimal btcReceived = leg1Qty.multiply(feeMultiplier).setScale(SCALE, ROUNDING);

        // Leg 2: Buy ALT with BTC
        BigDecimal leg2Qty = btcReceived.divide(leg2Price, SCALE, ROUNDING);
        BigDecimal altReceived = leg2Qty.multiply(feeMultiplier).setScale(SCALE, ROUNDING);

        // Leg 3: Sell ALT for USDT
        BigDecimal leg3Qty = altReceived;
        BigDecimal usdtBeforeFee = leg3Qty.multiply(leg3Price).setScale(SCALE, ROUNDING);
        BigDecimal usdtReceived = usdtBeforeFee.multiply(feeMultiplier).setScale(SCALE, ROUNDING);

        BigDecimal profit = usdtReceived.subtract(capital);
        BigDecimal profitBps = profit.divide(capital, SCALE, ROUNDING)
                .multiply(BPS_MULTIPLIER).setScale(2, ROUNDING);

        return new RouteResult(
                RouteDirection.ROUTE_B,
                profitBps,
                new BigDecimal[]{leg1Price, leg2Price, leg3Price},
                new BigDecimal[]{leg1Qty, leg2Qty, leg3Qty},
                profitBps.compareTo(BigDecimal.ZERO) > 0
        );
    }
}
