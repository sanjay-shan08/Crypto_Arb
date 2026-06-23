package com.arb.bitget.risk;

import com.arb.bitget.config.AppConfig;
import com.arb.bitget.engine.ArbitrageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CircuitBreakerTest {

    private ArbitrageEngine mockEngine;
    private AppConfig mockConfig;
    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        mockEngine = Mockito.mock(ArbitrageEngine.class);
        mockConfig = Mockito.mock(AppConfig.class);
        
        when(mockConfig.getMaxDailyLossUsdt()).thenReturn(new BigDecimal("50"));
        when(mockConfig.getMaxConsecutiveAborts()).thenReturn(3);

        circuitBreaker = new CircuitBreaker(mockConfig, mockEngine);
    }

    @Test
    void testConsecutiveAbortsTriggersKillSwitch() {
        circuitBreaker.onAbort();
        circuitBreaker.onAbort();
        
        // 2 aborts should not kill the engine
        verify(mockEngine, never()).kill();
        assertEquals(2, circuitBreaker.getConsecutiveAborts());

        circuitBreaker.onAbort();
        
        // 3 aborts should trigger kill
        verify(mockEngine, times(1)).kill();
        assertEquals(3, circuitBreaker.getConsecutiveAborts());
    }

    @Test
    void testSuccessfulTradeResetsConsecutiveAborts() {
        circuitBreaker.onAbort();
        circuitBreaker.onAbort();
        assertEquals(2, circuitBreaker.getConsecutiveAborts());

        circuitBreaker.onTradeSuccess(new BigDecimal("5.0"));
        
        assertEquals(0, circuitBreaker.getConsecutiveAborts());
        verify(mockEngine, never()).kill();
    }

    @Test
    void testDailyLossLimitTriggersKillSwitch() {
        circuitBreaker.onTradeSuccess(new BigDecimal("-20.0"));
        circuitBreaker.onTradeSuccess(new BigDecimal("-25.0"));

        // Net PnL is -45.0, allowed is 50. Should not kill.
        assertEquals(0, new BigDecimal("-45.0").compareTo(circuitBreaker.getNetDailyPnl()));
        verify(mockEngine, never()).kill();

        circuitBreaker.onTradeSuccess(new BigDecimal("-6.0"));

        // Net PnL is -51.0. Should kill.
        verify(mockEngine, times(1)).kill();
        assertEquals(0, new BigDecimal("-51.0").compareTo(circuitBreaker.getNetDailyPnl()));
    }

    @Test
    void testProfitsOffsetLosses() {
        circuitBreaker.onTradeSuccess(new BigDecimal("-40.0")); // Net: -40
        circuitBreaker.onTradeSuccess(new BigDecimal("20.0"));  // Net: -20
        circuitBreaker.onTradeSuccess(new BigDecimal("-25.0")); // Net: -45

        // Even though total losses are 65, net PnL is -45. Should not kill.
        verify(mockEngine, never()).kill();

        circuitBreaker.onTradeSuccess(new BigDecimal("-6.0"));  // Net: -51

        // Net PnL is -51. Should kill.
        verify(mockEngine, times(1)).kill();
    }
}
