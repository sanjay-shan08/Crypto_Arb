package com.arb.bitget.risk;

import com.arb.bitget.config.AppConfig;
import com.arb.bitget.engine.SignalQueue;
import com.arb.bitget.executor.TradeExecutionService;
import com.arb.bitget.model.RouteDirection;
import com.arb.bitget.model.Signal;
import com.arb.bitget.model.Triangle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RiskGateTest {

    private SignalQueue signalQueue;
    private TradeExecutionService executionService;
    private AppConfig config;
    private RiskGate riskGate;
    private Triangle triangle;

    @BeforeEach
    void setUp() throws Exception {
        signalQueue = mock(SignalQueue.class);
        executionService = mock(TradeExecutionService.class);
        config = mock(AppConfig.class);
        
        when(config.getMinProfitBps()).thenReturn(new BigDecimal("5"));
        when(config.getMaxPositionUsdt()).thenReturn(new BigDecimal("1000"));
        
        riskGate = new RiskGate(signalQueue, executionService, config);
        triangle = Triangle.parse("SOL/USDC/USDT");
    }

    private Signal createSignal(long detectedAt, String profitBps, String capital) {
        return new Signal(
                triangle,
                RouteDirection.ROUTE_A,
                new BigDecimal(profitBps),
                new BigDecimal[]{BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE},
                new BigDecimal[]{BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE},
                new BigDecimal(capital),
                detectedAt,
                0
        );
    }

    @Test
    void evaluate_acceptsValidSignal() throws Exception {
        Signal signal = createSignal(System.currentTimeMillis(), "10", "500");
        when(executionService.isInFlight()).thenReturn(false);
        when(signalQueue.take()).thenReturn(signal).thenThrow(new InterruptedException());
        
        riskGate.start();
        Thread.sleep(50);
        riskGate.stop();
        
        verify(executionService).execute(signal);
        verify(signalQueue, never()).put(any());
    }

    @Test
    void evaluate_rejectsStaleSignal() throws Exception {
        Signal signal = createSignal(System.currentTimeMillis() - 600, "10", "500");
        when(signalQueue.take()).thenReturn(signal).thenThrow(new InterruptedException());
        
        riskGate.start();
        Thread.sleep(50);
        riskGate.stop();
        
        verify(executionService, never()).execute(any());
        verify(signalQueue).put(argThat(s -> s.retryCount() == 1));
    }

    @Test
    void evaluate_rejectsLowProfitSignal() throws Exception {
        Signal signal = createSignal(System.currentTimeMillis(), "4", "500");
        when(signalQueue.take()).thenReturn(signal).thenThrow(new InterruptedException());
        
        riskGate.start();
        Thread.sleep(50);
        riskGate.stop();
        
        verify(executionService, never()).execute(any());
        verify(signalQueue).put(argThat(s -> s.retryCount() == 1));
    }

    @Test
    void evaluate_rejectsLargePositionSignal() throws Exception {
        Signal signal = createSignal(System.currentTimeMillis(), "10", "1500");
        when(signalQueue.take()).thenReturn(signal).thenThrow(new InterruptedException());
        
        riskGate.start();
        Thread.sleep(50);
        riskGate.stop();
        
        verify(executionService, never()).execute(any());
        verify(signalQueue).put(argThat(s -> s.retryCount() == 1));
    }

    @Test
    void evaluate_rejectsWhenTradeInFlight() throws Exception {
        Signal signal = createSignal(System.currentTimeMillis(), "10", "500");
        when(executionService.isInFlight()).thenReturn(true);
        when(signalQueue.take()).thenReturn(signal).thenThrow(new InterruptedException());
        
        riskGate.start();
        Thread.sleep(50);
        riskGate.stop();
        
        verify(executionService, never()).execute(any());
        verify(signalQueue).put(argThat(s -> s.retryCount() == 1));
    }
}
