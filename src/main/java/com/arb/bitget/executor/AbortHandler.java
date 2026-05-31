package com.arb.bitget.executor;

import com.arb.bitget.model.OrderResult;
import com.arb.bitget.model.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The most critical safety class in the entire bot.
 *
 * <p>Triggered when a leg fails AFTER a previous leg has already filled,
 * leaving the bot with an unhedged position. AbortHandler immediately
 * places a market order to flatten (reverse) the filled position.
 *
 * <p>Does NOT retry the original failed leg — capital preservation
 * takes absolute priority over profit capture.
 *
 * <p>Thread safety: called from the trade-executor thread.
 */
public class AbortHandler {

    private static final Logger log = LoggerFactory.getLogger(AbortHandler.class);

    private final OrderExecutor executor;

    public AbortHandler(OrderExecutor executor) {
        this.executor = executor;
    }

    /**
     * Flattens the position from a filled leg by placing a reverse market order.
     *
     * @param filledLeg  the successfully filled leg that needs to be reversed
     * @param failedPair the pair where execution failed (for logging)
     * @param failedSide the side that failed (for logging)
     */
    public void abort(OrderResult filledLeg, String failedPair, Side failedSide) {
        log.error("\u001B[31m\u001B[1mCRITICAL: ABORT triggered — leg failed on pair={}, side={}. " +
                        "Flattening position from filled leg: pair={}, side={}, qty={}\u001B[0m",
                failedPair, failedSide,
                filledLeg.pair(), filledLeg.side(), filledLeg.filledQuantity());

        // Reverse: sell what we bought, buy what we sold
        Side reverseSide = (filledLeg.side() == Side.BUY) ? Side.SELL : Side.BUY;

        try {
            // Market order (price=null) to flatten immediately
            OrderResult abortResult = executor.executeLeg(
                    filledLeg.pair(),
                    reverseSide,
                    filledLeg.filledQuantity(),
                    null  // market order — no price limit
            );

            if (abortResult.isSuccess()) {
                log.error("\u001B[33m\u001B[1mCRITICAL: Abort SUCCESSFUL — position flattened: pair={}, side={}, qty={}\u001B[0m",
                        filledLeg.pair(), reverseSide, filledLeg.filledQuantity());
            } else {
                log.error("\u001B[41m\u001B[37m\u001B[1mCRITICAL: Abort FAILED — UNHEDGED POSITION REMAINS: pair={}, side={}, qty={}. " +
                                "MANUAL INTERVENTION REQUIRED.\u001B[0m",
                        filledLeg.pair(), reverseSide, filledLeg.filledQuantity());
            }
        } catch (Exception e) {
            log.error("\u001B[41m\u001B[37m\u001B[1mCRITICAL: Abort execution threw exception — UNHEDGED POSITION: pair={}, qty={}. " +
                            "MANUAL INTERVENTION REQUIRED.\u001B[0m",
                    filledLeg.pair(), filledLeg.filledQuantity(), e);
        }
    }
}
