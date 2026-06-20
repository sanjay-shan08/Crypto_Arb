package com.arb.bitget.engine;

import com.arb.bitget.model.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * Thin wrapper around LinkedBlockingQueue that decouples the ArbitrageEngine
 * (producer) from the RiskGate/Executor (consumer).
 *
 * <p>Thread safety: backed by LinkedBlockingQueue, safe for concurrent use.
 * Producer calls put() from the engine thread, consumer calls take() from
 * the risk-gate thread.
 */
public class SignalQueue {

    private static final Logger log = LoggerFactory.getLogger(SignalQueue.class);

    private final LinkedBlockingQueue<Signal> queue = new LinkedBlockingQueue<>();

    /**
     * Enqueues a signal. Non-blocking (unbounded queue).
     */
    public void put(Signal signal) {
        queue.add(signal);
        log.debug("Signal enqueued: triangle={}, direction={}, profit={}bps, queueSize={}",
                signal.triangle().altBasePair(), signal.direction(),
                signal.expectedProfitBps(), queue.size());
    }

    /**
     * Dequeues the next signal, blocking if the queue is empty.
     */
    public Signal take() throws InterruptedException {
        return queue.take();
    }

    /**
     * Returns the current queue depth (for monitoring).
     */
    public int size() {
        return queue.size();
    }

    /**
     * Drains and discards all queued signals. Used during shutdown.
     */
    public void clear() {
        int count = queue.size();
        queue.clear();
        if (count > 0) {
            log.info("SignalQueue cleared: discarded {} signals", count);
        }
    }
}
