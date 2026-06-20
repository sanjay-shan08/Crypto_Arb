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
 * <p>Evaluates both Route A (BASE→ALT→INTER→BASE) and Route B
 * (BASE→INTER→ALT→BASE), returns whichever is more profitable.
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
     * @param capitalUsdt the base capital to deploy (e.g. USDT)
     * @param feeRate     per-trade fee rate (e.g. 0.001 for 0.1%)
     * @return the better of the two routes, or noPrices() if data is missing
     */
    public RouteResult calculate(Triangle triangle, PriceCache cache,
                                 BigDecimal capitalUsdt, BigDecimal feeRate) {
        PriceEntry altBase = cache.getPrice(triangle.altBasePair());
        PriceEntry altInter = cache.getPrice(triangle.altInterPair());
        PriceEntry interBase = cache.getPrice(triangle.interBasePair());

        if (altBase == null || altInter == null || interBase == null) {
            return RouteResult.noPrices();
        }

        BigDecimal feeMultiplier = ONE.subtract(feeRate);

        RouteResult routeA = calculateRouteA(capitalUsdt, feeMultiplier, altBase, altInter, interBase);
        RouteResult routeB = calculateRouteB(capitalUsdt, feeMultiplier, altBase, altInter, interBase);

        // Return the more profitable route
        if (routeA.profitBps().compareTo(routeB.profitBps()) >= 0) {
            return routeA;
        }
        return routeB;
    }

    /**
     * Route A: BASE → ALT → INTER → BASE
     *
     * Leg 1: Buy ALT with BASE  (ask price on ALT/BASE)
     * Leg 2: Sell ALT for INTER (bid price on ALT/INTER)
     * Leg 3: Sell INTER for BASE (bid price on INTER/BASE)
     */
    private RouteResult calculateRouteA(BigDecimal capital, BigDecimal feeMultiplier,
                                        PriceEntry altBase, PriceEntry altInter, PriceEntry interBase) {
        BigDecimal leg1Price = altBase.bestAsk();
        BigDecimal leg2Price = altInter.bestBid();
        BigDecimal leg3Price = interBase.bestBid();

        // Leg 1: Buy ALT with BASE
        BigDecimal leg1Qty = capital.divide(leg1Price, SCALE, ROUNDING);
        BigDecimal altReceived = leg1Qty.multiply(feeMultiplier).setScale(SCALE, ROUNDING);

        // Leg 2: Sell ALT for INTER
        BigDecimal leg2Qty = altReceived;
        BigDecimal interBeforeFee = leg2Qty.multiply(leg2Price).setScale(SCALE, ROUNDING);
        BigDecimal interReceived = interBeforeFee.multiply(feeMultiplier).setScale(SCALE, ROUNDING);

        // Leg 3: Sell INTER for BASE
        BigDecimal leg3Qty = interReceived;
        BigDecimal baseBeforeFee = leg3Qty.multiply(leg3Price).setScale(SCALE, ROUNDING);
        BigDecimal baseReceived = baseBeforeFee.multiply(feeMultiplier).setScale(SCALE, ROUNDING);

        BigDecimal profit = baseReceived.subtract(capital);
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
     * Route B: BASE → INTER → ALT → BASE
     *
     * Leg 1: Buy INTER with BASE (ask price on INTER/BASE)
     * Leg 2: Buy ALT with INTER  (ask price on ALT/INTER)
     * Leg 3: Sell ALT for BASE   (bid price on ALT/BASE)
     */
    private RouteResult calculateRouteB(BigDecimal capital, BigDecimal feeMultiplier,
                                        PriceEntry altBase, PriceEntry altInter, PriceEntry interBase) {
        BigDecimal leg1Price = interBase.bestAsk();
        BigDecimal leg2Price = altInter.bestAsk();
        BigDecimal leg3Price = altBase.bestBid();

        // Leg 1: Buy INTER with BASE
        BigDecimal leg1Qty = capital.divide(leg1Price, SCALE, ROUNDING);
        BigDecimal interReceived = leg1Qty.multiply(feeMultiplier).setScale(SCALE, ROUNDING);

        // Leg 2: Buy ALT with INTER
        BigDecimal leg2Qty = interReceived.divide(leg2Price, SCALE, ROUNDING);
        BigDecimal altReceived = leg2Qty.multiply(feeMultiplier).setScale(SCALE, ROUNDING);

        // Leg 3: Sell ALT for BASE
        BigDecimal leg3Qty = altReceived;
        BigDecimal baseBeforeFee = leg3Qty.multiply(leg3Price).setScale(SCALE, ROUNDING);
        BigDecimal baseReceived = baseBeforeFee.multiply(feeMultiplier).setScale(SCALE, ROUNDING);

        BigDecimal profit = baseReceived.subtract(capital);
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
