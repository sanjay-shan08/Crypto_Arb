package com.arb.bitget.executor;

import com.arb.bitget.model.OrderResult;
import com.arb.bitget.model.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

/**
 * Executor that places real orders on Bitget's sandbox/testnet.
 * Uses the same logic as LiveExecutor but targets testnet endpoints.
 *
 * <p>Thread safety: called sequentially from the trade-executor thread.
 */
public class SandboxExecutor implements OrderExecutor {

    private static final Logger log = LoggerFactory.getLogger(SandboxExecutor.class);

    private final BitgetApiClient apiClient;

    public SandboxExecutor(BitgetApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public OrderResult executeLeg(String pair, Side side, BigDecimal quantity, BigDecimal price) {
        try {
            String orderType = (price == null) ? "market" : "limit";
            String force = (price == null) ? "gtc" : "ioc";

            log.info("[SANDBOX] Placing order: pair={}, side={}, type={}, qty={}, price={}",
                    pair, side, orderType, quantity, price);

            return apiClient.placeOrder(pair, side, orderType, quantity, price, force);
        } catch (Exception e) {
            log.error("[SANDBOX] Order execution failed: pair={}, side={}", pair, side, e);
            return new OrderResult(null, null, OrderResult.Status.FAILED,
                    BigDecimal.ZERO, BigDecimal.ZERO, pair, side);
        }
    }
}
