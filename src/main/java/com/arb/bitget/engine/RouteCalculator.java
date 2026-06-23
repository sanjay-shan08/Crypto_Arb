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

    private final boolean enableDepthCheck;

    public RouteCalculator(boolean enableDepthCheck) {
        this.enableDepthCheck = enableDepthCheck;
    }

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
        BigDecimal altBeforeFee = getVwapBuyWithQuote(altBase, capital);
        if (altBeforeFee == null) return RouteResult.noPrices();
        BigDecimal altReceived = altBeforeFee.multiply(feeMultiplier).setScale(SCALE, ROUNDING);
        BigDecimal leg1Qty = altBeforeFee;

        // Leg 2: Sell ALT for INTER
        BigDecimal leg2Qty = altReceived;
        BigDecimal interBeforeFee = getVwapSellWithBase(altInter, leg2Qty);
        if (interBeforeFee == null) return RouteResult.noPrices();
        BigDecimal interReceived = interBeforeFee.multiply(feeMultiplier).setScale(SCALE, ROUNDING);

        // Leg 3: Sell INTER for BASE
        BigDecimal leg3Qty = interReceived;
        BigDecimal baseBeforeFee = getVwapSellWithBase(interBase, leg3Qty);
        if (baseBeforeFee == null) return RouteResult.noPrices();
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
        BigDecimal interBeforeFee = getVwapBuyWithQuote(interBase, capital);
        if (interBeforeFee == null) return RouteResult.noPrices();
        BigDecimal interReceived = interBeforeFee.multiply(feeMultiplier).setScale(SCALE, ROUNDING);
        BigDecimal leg1Qty = interBeforeFee;

        // Leg 2: Buy ALT with INTER
        BigDecimal altBeforeFee = getVwapBuyWithQuote(altInter, interReceived);
        if (altBeforeFee == null) return RouteResult.noPrices();
        BigDecimal altReceived = altBeforeFee.multiply(feeMultiplier).setScale(SCALE, ROUNDING);
        BigDecimal leg2Qty = altBeforeFee;

        // Leg 3: Sell ALT for BASE
        BigDecimal leg3Qty = altReceived;
        BigDecimal baseBeforeFee = getVwapSellWithBase(altBase, leg3Qty);
        if (baseBeforeFee == null) return RouteResult.noPrices();
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

    /**
     * Calculates how much BASE asset we receive if we spend quoteAmount of QUOTE asset.
     */
    private BigDecimal getVwapBuyWithQuote(PriceEntry entry, BigDecimal quoteAmount) {
        if (!enableDepthCheck || entry.asks() == null) {
            return quoteAmount.divide(entry.bestAsk(), SCALE, ROUNDING);
        }
        BigDecimal remainingQuote = quoteAmount;
        BigDecimal totalBaseReceived = BigDecimal.ZERO;
        for (int i = 0; i < entry.asks().length; i++) {
            BigDecimal price = entry.asks()[i];
            BigDecimal size = entry.askSizes()[i];
            BigDecimal levelQuoteVolume = price.multiply(size);

            if (remainingQuote.compareTo(levelQuoteVolume) <= 0) {
                BigDecimal takeBase = remainingQuote.divide(price, SCALE, ROUNDING);
                totalBaseReceived = totalBaseReceived.add(takeBase);
                return totalBaseReceived;
            } else {
                totalBaseReceived = totalBaseReceived.add(size);
                remainingQuote = remainingQuote.subtract(levelQuoteVolume);
            }
        }
        return null; // Not enough liquidity
    }

    /**
     * Calculates how much QUOTE asset we receive if we sell baseAmount of BASE asset.
     */
    private BigDecimal getVwapSellWithBase(PriceEntry entry, BigDecimal baseAmount) {
        if (!enableDepthCheck || entry.bids() == null) {
            return baseAmount.multiply(entry.bestBid());
        }
        BigDecimal remainingBase = baseAmount;
        BigDecimal totalQuoteReceived = BigDecimal.ZERO;
        for (int i = 0; i < entry.bids().length; i++) {
            BigDecimal price = entry.bids()[i];
            BigDecimal size = entry.bidSizes()[i];

            if (remainingBase.compareTo(size) <= 0) {
                totalQuoteReceived = totalQuoteReceived.add(remainingBase.multiply(price));
                return totalQuoteReceived;
            } else {
                totalQuoteReceived = totalQuoteReceived.add(size.multiply(price));
                remainingBase = remainingBase.subtract(size);
            }
        }
        return null; // Not enough liquidity
    }
}
