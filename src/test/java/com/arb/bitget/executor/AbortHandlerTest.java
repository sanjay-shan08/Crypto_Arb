package com.arb.bitget.executor;

import com.arb.bitget.model.OrderResult;
import com.arb.bitget.model.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class AbortHandlerTest {

    private OrderExecutor executor;
    private AbortHandler abortHandler;

    @BeforeEach
    void setUp() {
        executor = mock(OrderExecutor.class);
        abortHandler = new AbortHandler(executor);
    }

    @Test
    void abort_reversesFilledBuyWithSell() throws Exception {
        OrderResult filledLeg = new OrderResult(
                "order1", "client1", OrderResult.Status.FILLED,
                new BigDecimal("10.5"), new BigDecimal("50000"),
                "BTCUSDT", Side.BUY
        );

        OrderResult mockAbortResult = new OrderResult(
                "order2", "client2", OrderResult.Status.FILLED,
                new BigDecimal("10.5"), new BigDecimal("49900"),
                "BTCUSDT", Side.SELL
        );

        when(executor.executeLeg(eq("BTCUSDT"), eq(Side.SELL), eq(new BigDecimal("10.5")), isNull()))
                .thenReturn(mockAbortResult);

        abortHandler.abort(filledLeg, "ETHUSDT", Side.BUY);

        // Verify that the abort handler places a market SELL order (null price) for the exact filled quantity
        verify(executor).executeLeg("BTCUSDT", Side.SELL, new BigDecimal("10.5"), null);
    }

    @Test
    void abort_reversesFilledSellWithBuy() throws Exception {
        OrderResult filledLeg = new OrderResult(
                "order1", "client1", OrderResult.Status.FILLED,
                new BigDecimal("100"), new BigDecimal("1.5"),
                "XRPUSDT", Side.SELL
        );

        OrderResult mockAbortResult = new OrderResult(
                "order2", "client2", OrderResult.Status.FILLED,
                new BigDecimal("100"), new BigDecimal("1.51"),
                "XRPUSDT", Side.BUY
        );

        when(executor.executeLeg(eq("XRPUSDT"), eq(Side.BUY), eq(new BigDecimal("100")), isNull()))
                .thenReturn(mockAbortResult);

        abortHandler.abort(filledLeg, "XRPUSDC", Side.SELL);

        // Verify that the abort handler places a market BUY order (null price) for the exact filled quantity
        verify(executor).executeLeg("XRPUSDT", Side.BUY, new BigDecimal("100"), null);
    }
    
    @Test
    void abort_handlesExecutorExceptionGracefully() throws Exception {
        OrderResult filledLeg = new OrderResult(
                "order1", "client1", OrderResult.Status.FILLED,
                new BigDecimal("10.5"), new BigDecimal("50000"),
                "BTCUSDT", Side.BUY
        );

        // Simulate network failure during the abort itself
        when(executor.executeLeg(anyString(), any(Side.class), any(BigDecimal.class), isNull()))
                .thenThrow(new RuntimeException("API Unreachable"));

        // Should catch the exception and log it, rather than propagating it and crashing the thread
        abortHandler.abort(filledLeg, "ETHUSDT", Side.BUY);
        
        verify(executor).executeLeg("BTCUSDT", Side.SELL, new BigDecimal("10.5"), null);
    }
}
