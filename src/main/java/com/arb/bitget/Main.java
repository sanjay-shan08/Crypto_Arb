package com.arb.bitget;

import com.arb.bitget.config.AppConfig;
import com.arb.bitget.engine.ArbitrageEngine;
import com.arb.bitget.engine.RouteCalculator;
import com.arb.bitget.engine.SignalQueue;
import com.arb.bitget.executor.AbortHandler;
import com.arb.bitget.executor.BitgetApiClient;
import com.arb.bitget.executor.LiveExecutor;
import com.arb.bitget.executor.OrderExecutor;
import com.arb.bitget.executor.PaperExecutor;
import com.arb.bitget.executor.SandboxExecutor;
import com.arb.bitget.executor.TradeExecutionService;
import com.arb.bitget.market.BitgetWebSocketClient;
import com.arb.bitget.market.HeartbeatMonitor;
import com.arb.bitget.market.PriceCache;
import com.arb.bitget.market.ReconnectHandler;
import com.arb.bitget.model.Triangle;
import com.arb.bitget.risk.NetworkChecker;
import com.arb.bitget.risk.RiskGate;
import com.arb.bitget.risk.StartupChecker;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Application entry point.
 * Parses --mode flag, wires all dependencies, and runs the startup sequence.
 *
 * <p>This is the ONLY class that changes behavior based on mode.
 * Every other class receives its dependencies through constructor injection
 * and is completely mode-agnostic.
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        // Global uncaught exception handler
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                log.error("CRITICAL: Uncaught exception in thread {}", t.getName(), e));

        // 1. Parse mode
        String mode = parseMode(args);

        log.info("========================================");
        log.info("  Bitget Triangular Arbitrage Bot");
        log.info("  Mode: {}", mode.toUpperCase());
        log.info("========================================");

        try {
            run(mode);
        } catch (Exception e) {
            log.error("CRITICAL: Fatal error during startup", e);
            System.exit(1);
        }
    }

    private static void run(String mode) throws Exception {
        // 2. Load config
        AppConfig config = AppConfig.from(mode);
        log.info("Config loaded: triangles={}, tickMs={}, minProfitBps={}, feeRate={}",
                config.getTriangles().size(), config.getEngineTickMs(),
                config.getMinProfitBps(), config.getFeeRate());

        // 3. Collect all unique pairs for WebSocket subscription
        List<String> allPairs = new ArrayList<>();
        for (Triangle triangle : config.getTriangles()) {
            for (String pair : triangle.allPairs()) {
                if (!allPairs.contains(pair)) {
                    allPairs.add(pair);
                }
            }
        }
        log.info("Pairs to subscribe: {} unique across {} triangles", allPairs.size(), config.getTriangles().size());

        // 4. Create shared OkHttpClient with connection pooling
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();

        // 5. Instantiate PriceCache
        PriceCache priceCache = new PriceCache();

        // 6. Instantiate API client and OrderExecutor based on mode
        BitgetApiClient apiClient = null;
        OrderExecutor orderExecutor;

        if (config.isPaperMode()) {
            orderExecutor = new PaperExecutor();
            log.info("Executor: PaperExecutor (no API calls)");
        } else {
            apiClient = new BitgetApiClient(httpClient, config);
            if ("sandbox".equals(mode)) {
                orderExecutor = new SandboxExecutor(apiClient);
                log.info("Executor: SandboxExecutor (testnet)");
            } else {
                orderExecutor = new LiveExecutor(apiClient);
                log.info("Executor: LiveExecutor (PRODUCTION — REAL MONEY)");
            }
        }

        // 7. Instantiate AbortHandler
        AbortHandler abortHandler = new AbortHandler(orderExecutor);

        // 8. Instantiate SignalQueue
        SignalQueue signalQueue = new SignalQueue();

        // 9. Instantiate RouteCalculator
        RouteCalculator routeCalculator = new RouteCalculator(config.isEnableDepthCheck());

        // 10. Instantiate ArbitrageEngine
        ArbitrageEngine engine = new ArbitrageEngine(priceCache, routeCalculator, signalQueue, config);

        // 11. Instantiate CircuitBreaker
        com.arb.bitget.risk.CircuitBreaker circuitBreaker = new com.arb.bitget.risk.CircuitBreaker(config, engine);

        // 12. Instantiate TradeExecutionService
        TradeExecutionService executionService = new TradeExecutionService(orderExecutor, abortHandler, circuitBreaker, config.getFeeRate());

        // 13. Instantiate BalanceManager
        com.arb.bitget.risk.BalanceManager balanceManager = new com.arb.bitget.risk.BalanceManager(apiClient);

        // 14. Instantiate RiskGate
        RiskGate riskGate = new RiskGate(signalQueue, executionService, balanceManager, config);

        // 14. Instantiate HeartbeatMonitor
        HeartbeatMonitor heartbeatMonitor = new HeartbeatMonitor(
                priceCache, config.getHeartbeatTimeoutMs(), engine::kill);

        // 14. Instantiate ReconnectHandler
        ReconnectHandler reconnectHandler = new ReconnectHandler();

        // 15. Instantiate WebSocketClient
        BitgetWebSocketClient wsClient = new BitgetWebSocketClient(
                config.getWsUrl(), allPairs, priceCache, reconnectHandler, httpClient, config.isEnableDepthCheck());

        // === Register shutdown hook ===
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received...");
            engine.stop();
            heartbeatMonitor.stop();
            wsClient.close();
            signalQueue.clear();
            riskGate.stop();
            reconnectHandler.stop();
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
            log.info("========================================");
            log.info("  Arbitrage Statistics Summary");
            log.info("----------------------------------------");
            log.info("  Total Attempted: {}", executionService.getAttemptedCount());
            log.info("  Successful:      {}", executionService.getSuccessfulCount());
            log.info("  Failed:          {}", executionService.getFailedCount());
            log.info("========================================");
            log.info("  Shutdown complete.");
            log.info("========================================");
        }, "shutdown-hook"));

        // =====================================================================
        // STARTUP SEQUENCE
        // =====================================================================

        // Step 1: Cancel stale orders (skip for paper)
        if (!config.isPaperMode() && apiClient != null) {
            StartupChecker startupChecker = new StartupChecker(apiClient, config.getTriangles());
            startupChecker.run();
        } else {
            log.info("Paper mode — skipping StartupChecker");
        }

        // Step 2: Network preflight (skip for paper)
        NetworkChecker networkChecker = null;
        if (!config.isPaperMode() && apiClient != null) {
            networkChecker = new NetworkChecker(
                    apiClient, config.getMaxLatencyMs(),
                    config.getPreflightSamples(), config.getRuntimeSamples(),
                    config.getNetworkCheckIntervalS(),
                    engine::pause, engine::resume);

            if (!networkChecker.preflight()) {
                log.error("CRITICAL: Network preflight FAILED — exiting");
                System.exit(1);
            }
        } else {
            log.info("Paper mode — skipping NetworkChecker preflight");
        }

        // Step 2.5: Initialize BalanceManager
        balanceManager.initialize();

        // Step 3: Connect WebSocket and wait for first price
        wsClient.connect();
        log.info("Waiting for first price update...");
        boolean received = wsClient.awaitFirstPrice(30_000);
        if (!received) {
            log.error("CRITICAL: No price received within 30s — exiting");
            System.exit(1);
        }

        // Step 4: Start HeartbeatMonitor
        heartbeatMonitor.start();

        // Step 5: Start RiskGate consumer
        riskGate.start();

        // Step 6: Start NetworkChecker monitoring (skip for paper)
        if (networkChecker != null) {
            networkChecker.startMonitoring();
        }

        // Step 7: Start ArbitrageEngine
        engine.start();

        log.info("========================================");
        log.info("  Bot is LIVE. Press Ctrl+C to stop.");
        log.info("  Scanning {} triangles every {}ms",
                config.getTriangles().size(), config.getEngineTickMs());
        log.info("========================================");

        // Block main thread (all work happens on daemon threads)
        Thread.currentThread().join();
    }

    private static String parseMode(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--mode".equals(args[i])) {
                String mode = args[i + 1].toLowerCase();
                if ("paper".equals(mode) || "sandbox".equals(mode) || "live".equals(mode)) {
                    return mode;
                }
                log.error("Invalid mode: '{}'. Must be 'paper', 'sandbox', or 'live'", args[i + 1]);
                System.exit(1);
            }
        }
        log.info("No --mode flag specified, defaulting to 'paper'");
        return "paper";
    }
}
