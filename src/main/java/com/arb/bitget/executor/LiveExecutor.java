package com.arb.bitget.executor;

import com.arb.bitget.model.OrderResult;
import com.arb.bitget.model.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

/**
 * Production executor — places real orders with real money on Bitget.
 * Uses limit+ioc for normal legs, market for abort/recovery legs.
 *
 * <p>When price is null, automatically switches to market order.
 * This is used by AbortHandler for emergency position flattening.
 *
 * <p>Thread safety: called sequentially from the trade-executor thread.
 */
public class LiveExecutor implements OrderExecutor {

    private static final Logger log = LoggerFactory.getLogger(LiveExecutor.class);

    private final BitgetApiClient apiClient;

    public LiveExecutor(BitgetApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public OrderResult executeLeg(String pair, Side side, BigDecimal quantity, BigDecimal price) {
        try {
            String orderType = (price == null) ? "market" : "limit";
            String force = (price == null) ? "gtc" : "ioc";

            log.info("[LIVE] Placing order: pair={}, side={}, type={}, qty={}, price={}",
                    pair, side, orderType, quantity, price);

            return apiClient.placeOrder(pair, side, orderType, quantity, price, force);
        } catch (Exception e) {
            log.error("[LIVE] Order execution failed: pair={}, side={}", pair, side, e);
            return new OrderResult(null, null, OrderResult.Status.FAILED,
                    BigDecimal.ZERO, BigDecimal.ZERO, pair, side);
        }
    }
}
