# ARCHITECTURE.md
> Layers, services, data flow, module boundaries, and design rationale.
> Last updated: 2025-05-25

---

## 1. Architectural Overview

The bot is a **four-layer pipeline** with strict unidirectional data flow:

```
┌─────────────────────────────────────────────────────────────────┐
│                        MARKET DATA LAYER                        │
│  BitgetWebSocketClient → PriceCache                            │
│  HeartbeatMonitor, ReconnectHandler                            │
│  Package: com.arb.bitget.market                                │
└──────────────────────────┬──────────────────────────────────────┘
                           │ reads PriceCache
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                         ENGINE LAYER                            │
│  ArbitrageEngine (100ms tick) → RouteCalculator → SignalQueue  │
│  Package: com.arb.bitget.engine                                │
└──────────────────────────┬──────────────────────────────────────┘
                           │ dequeues Signal
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                          RISK LAYER                             │
│  RiskGate (accept / reject+requeue)                            │
│  StartupChecker (boot-time only)                               │
│  NetworkChecker (latency monitor)                              │
│  Package: com.arb.bitget.risk                                  │
└──────────────────────────┬──────────────────────────────────────┘
                           │ approved Signal
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                        EXECUTOR LAYER                           │
│  TradeExecutionService (orchestrates 3 legs)                   │
│  OrderExecutor ← PaperExecutor / SandboxExecutor / LiveExecutor│
│  BitgetApiClient (REST implementation)                         │
│  AbortHandler (leg-2/3 failure recovery)                       │
│  Package: com.arb.bitget.executor                              │
└─────────────────────────────────────────────────────────────────┘
```

### Design Principles
- **Unidirectional flow**: Data moves top → bottom. No layer calls upward.
- **Decoupled via queue**: Engine and Executor are decoupled by `SignalQueue`.
- **Interface-based injection**: Only `OrderExecutor` is polymorphic; mode is resolved once at startup in `Main.java`.
- **Fail-safe defaults**: Stale data kills the engine. Failed leg 2 triggers abort. Rejected signals are re-queued.

---

## 2. Layer Details

### 2.1 Market Data Layer (`com.arb.bitget.market`)

**Responsibility**: Maintain a live, always-current price feed from Bitget and expose it via `PriceCache`.

#### Classes

##### `BitgetWebSocketClient`
- Connects to Bitget WebSocket API (`wss://ws.bitget.com/v2/ws/public`).
- Subscribes to `ticker` channels for all trading pairs in the triangle.
- On each message: parses JSON → updates `PriceCache` → updates heartbeat timestamp.
- Delegates reconnection to `ReconnectHandler`.

##### `PriceCache`
- Thread-safe price store backed by `ConcurrentHashMap<String, PriceEntry>`.
- `PriceEntry` contains: `bestBid`, `bestAsk`, `timestamp`.
- Provides `getPrice(pair)` — returns an immutable snapshot.
- Provides `getLastUpdateTime()` — used by `HeartbeatMonitor`.

##### `HeartbeatMonitor`
- Runs on a dedicated scheduled thread.
- Every 1 second, checks `PriceCache.getLastUpdateTime()`.
- If `now - lastUpdate > 5000ms` → calls `ArbitrageEngine.kill()`.
- Logs a CRITICAL-level message on trigger.

##### `ReconnectHandler`
- Invoked on WebSocket `onFailure` / `onClosed`.
- Implements exponential backoff: `min(2^attempt * 100ms, 30s)`.
- Resets backoff counter on successful reconnection.
- Logs each reconnection attempt with attempt number and delay.

#### Data Contract (outbound)
```java
// PriceCache exposes:
PriceEntry getPrice(String pair);     // e.g., "BTCUSDT"
long getLastUpdateTime();              // epoch millis
```

---

### 2.2 Engine Layer (`com.arb.bitget.engine`)

**Responsibility**: Detect arbitrage opportunities and produce execution signals.

#### Classes

##### `ArbitrageEngine`
- Runs on `ScheduledExecutorService` at a **fixed rate of 100ms**.
- Each tick:
  1. Iterates over all configured triangles (e.g., SOL/USDC/USDT, XRP/BTC/USDT, etc.).
  2. For each triangle, reads relevant prices from `PriceCache`.
  3. Passes prices to `RouteCalculator.calculate(triangle)`.
  4. If either route is profitable → creates a `Signal` → enqueues it on `SignalQueue`.
- Exposes `start()`, `stop()`, `kill()` lifecycle methods.
- `kill()` is called by `HeartbeatMonitor` — cancels the scheduled task immediately.

##### `RouteCalculator`
- Pure function — no state, no side effects.
- Accepts a `Triangle` object defining the 3 pairs to evaluate.
- Calculates two routes for any given triangle (e.g., SOL/USDC/USDT):
  - **Route A**: BASE → ALT → INTER → BASE (e.g., USDT → SOL → USDC → USDT)
  - **Route B**: BASE → INTER → ALT → BASE (e.g., USDT → USDC → SOL → USDT)
- All math uses `BigDecimal` with `RoundingMode.HALF_UP` and scale of 8.
- Returns a `RouteResult` containing: route direction, expected profit (bps), leg prices, leg quantities.
- Calculates **Net Expected Profit (bps)** by explicitly subtracting configured transaction fees:
  `Net Profit = Gross Profit - (exchange.fee.rate * exchange.fee.legs)`
  This ensures that transaction costs (0.10% default spot fee per leg, or 0.08% with BGB Bps discount) are fully accounted for before a signal is generated.

##### `Triangle` (model class)
- Immutable record defining a triangular path: `Triangle(String pairA, String pairB, String pairC)`.
- Example: `new Triangle("SOLUSDT", "SOLUSDC", "USDCUSDT")` (for `SOL/USDC/USDT`).
- Configured via `config.properties` — new triangles can be added without code changes.
- `PriceCache` subscribes to ticker channels for **all pairs across all configured triangles**.

##### `SignalQueue`
- Thin wrapper around `LinkedBlockingQueue<Signal>`.
- Producer: `ArbitrageEngine.enqueue(signal)`.
- Consumer: `ExecutorThread.take()` (blocking).
- Capacity: unbounded (signals are cheap; stale ones are filtered by the risk layer).
- Provides `size()` for monitoring.

#### Data Contract (outbound)
```java
// Signal record:
record Signal(
    RouteDirection direction,   // ROUTE_A or ROUTE_B
    BigDecimal expectedProfit,  // in basis points
    BigDecimal[] legPrices,     // [leg1Price, leg2Price, leg3Price]
    BigDecimal[] legQuantities, // [leg1Qty, leg2Qty, leg3Qty]
    long detectedAt             // epoch millis
);
```

---

### 2.3 Risk Layer (`com.arb.bitget.risk`)

**Responsibility**: Gate signals before execution. Ensure no signal passes that violates safety constraints.

#### Classes

##### `RiskGate`
- Consumes signals from `SignalQueue`.
- Applies checks in order:
  1. **Staleness check**: `now - signal.detectedAt < MAX_SIGNAL_AGE_MS` (e.g., 500ms).
  2. **Minimum net profit check**: `signal.expectedProfit >= MIN_PROFIT_BPS` (ensures the net profit after all fees is at least the target take-home margin, e.g., 5 bps).
  3. **Position size check**: total exposure ≤ `MAX_POSITION_USDT`.
  4. **Concurrent execution check**: no other trade currently in-flight.
- **On accept**: passes signal to `OrderExecutor`.
- **On reject**: re-enqueues the signal back to `SignalQueue` (with a re-queue counter to prevent infinite loops; drop after N retries).

##### `StartupChecker`
- Runs **once** during boot, **before** the engine starts.
- Queries Bitget REST API for all open orders.
- Cancels any stale orders from a previous crash.
- Logs every cancelled order with orderId and pair.
- Only after `StartupChecker.run()` completes does `Main.java` start the engine.

##### `NetworkChecker`
- Measures REST API round-trip latency to Bitget servers.
- **At startup**: Runs before the engine starts. Sends 5 pings to `GET /api/v2/public/time` (Bitget's server-time endpoint — lightweight, no auth required). Calculates average round-trip.
- **If average latency > `MAX_ACCEPTABLE_LATENCY_MS` (default: 400ms)** → refuses to start. Logs CRITICAL with measured latency and threshold. Exits with non-zero code.
- **During operation**: Runs on a scheduled thread every 30 seconds. Sends 3 pings, calculates rolling average.
- **If runtime latency exceeds threshold** → pauses `ArbitrageEngine` (stops the scheduled tick). Does NOT close WebSocket (prices keep updating). Resumes automatically when next check passes.
- **If latency recovers below threshold** → resumes engine, logs INFO with recovered latency.
- Uses the same `OkHttpClient` instance as the executor (connection-pooled) so measurements reflect real trading conditions.

#### Rejection Flow
```
SignalQueue
    │
    ▼
RiskGate.evaluate(signal)
    │
    ├── ACCEPT → TradeExecutionService.execute(signal)
    │
    └── REJECT → signal.incrementRetry()
                   │
                   ├── retries < MAX → SignalQueue.put(signal)  // re-queue
                   └── retries >= MAX → drop + log warning
```

---

### 2.4 Executor Layer (`com.arb.bitget.executor`)

**Responsibility**: Execute approved signals as actual trades (or simulated ones).

#### Interface

```java
public interface OrderExecutor {
    OrderResult executeLeg(String pair, Side side, BigDecimal quantity, BigDecimal price);
}
```

All three implementations are **drop-in replacements** — same interface, same method signatures.

#### Implementations

##### `TradeExecutionService`
- Orchestrates the full 3-leg sequence.
- Tracks in-flight status using `AtomicBoolean` to prevent overlapping executions.
- Invokes `AbortHandler` if Leg 2 or Leg 3 fails to fill successfully.

##### `PaperExecutor`
- Logs the trade to console/file.
- Returns a synthetic `OrderResult` with `FILLED` status.
- Useful for strategy validation without any API calls.
- Simulates fill at the requested price (no slippage simulation).
- **Simulates ~200ms network delay** per leg via `Thread.sleep(200)` to produce
  realistic timing for paper-trade P&L analysis.

##### `SandboxExecutor`
- Makes **real HTTP calls** to Bitget's sandbox/testnet endpoints.
- Uses `BitgetApiClient` for network operations.
- Validates the full request/response cycle without risking real funds.
- Same OkHttp client configuration as `LiveExecutor`.

##### `LiveExecutor`
- Production executor — real money, real orders.
- Uses `BitgetApiClient` for authenticating and placing orders.
- Uses **OkHttp connection pooling** (`ConnectionPool` with keep-alive) for minimum latency.
- **CRITICAL**: Must configure `ConnectionPool(5, 5, TimeUnit.MINUTES)` — this single
  config saves ~60ms per leg (~180ms total) by avoiding TCP handshake overhead.

##### `BitgetApiClient`
- Centralizes Bitget REST API communication.
- Signs requests with HMAC-SHA256 per Bitget API spec.
- Implements retry logic for transient HTTP errors (429, 500, 503) with limited attempts.

##### `AbortHandler`
- **The most critical safety class in the entire bot.**
- Triggered when: leg 1 filled successfully, but leg 2 or 3 fails.
- Action: immediately places a **market sell** order to flatten the position.
- Does NOT wait, does NOT retry the original leg — it prioritizes capital preservation.
- Logs the abort event at CRITICAL level with full trade context.

#### Execution Flow
```
Signal (approved by RiskGate)
    │
    ▼
TradeExecutionService.executeTriangle(signal)
    │
    ├── Leg 1: OrderExecutor.executeLeg(leg1)
    │   ├── SUCCESS → Leg 2: OrderExecutor.executeLeg(leg2)
    │   │             ├── SUCCESS → Leg 3: OrderExecutor.executeLeg(leg3)
    │   │             │             ├── SUCCESS → log profit, done
    │   │             │             └── FAIL → AbortHandler.abort(leg2Result)
    │   │             │
    │   │             └── FAIL → AbortHandler.abort(leg1Result)
    │   │
    │   └── FAIL → log failure, no position taken, safe
```

#### Leg Risk (Key Concept)
Leg risk is the danger that after leg 1 fills, prices move before leg 2 executes:

```
0ms     — Opportunity detected
0.1ms   — Engine calculates profit = +0.4%
150ms   — Leg 1 sent & filled ✅
300ms   — Leg 2 sent...
310ms   — Another bot already moved the price
320ms   — Leg 2 fills at WORSE price → profit wiped
```

This is why `AbortHandler` exists: it's the safety net for when leg risk materializes.
Mitigation strategies:
- Target altcoin pairs with 5–30 second windows (not BTC/ETH/USDT at 2–5s)
- Set MIN_SPREAD high enough to absorb worst-case slippage
- Use connection pooling to minimize the gap between leg executions

---

## 3. Module Dependency Graph

```
Main.java
    │
    ├── config/AppConfig
    │
    ├── market/
    │     ├── BitgetWebSocketClient ──► PriceCache
    │     ├── HeartbeatMonitor ──► PriceCache, ArbitrageEngine
    │     └── ReconnectHandler ──► BitgetWebSocketClient
    │
    ├── engine/
    │     ├── ArbitrageEngine ──► PriceCache, RouteCalculator, SignalQueue
    │     └── RouteCalculator (stateless)
    │
    ├── risk/
    │     ├── RiskGate ──► SignalQueue, OrderExecutor
    │     ├── StartupChecker ──► Bitget REST API
    │     └── NetworkChecker ──► OkHttp, Bitget REST API, ArbitrageEngine
    │
    └── executor/
          ├── TradeExecutionService ──► OrderExecutor, AbortHandler
          ├── OrderExecutor (interface)
          ├── BitgetApiClient ──► OkHttp
          ├── PaperExecutor
          ├── SandboxExecutor ──► BitgetApiClient
          ├── LiveExecutor ──► BitgetApiClient
          └── AbortHandler ──► OrderExecutor
```

### Dependency Rules
1. **No circular dependencies.** Dependency flow is strictly top-to-bottom (market → engine → risk → executor).
2. **Cross-layer communication** is via `PriceCache` (read) and `SignalQueue` (produce/consume) only.
3. **AbortHandler** depends on `OrderExecutor` — it uses the same executor to place the market-sell.
4. **No layer imports from a layer above it.** The executor layer never imports from the engine layer.

---

## 4. Startup Sequence

```
Main.main(String[] args)
    │
    ├── 1. Parse --mode flag
    ├── 2. Load AppConfig (properties file for the mode)
    ├── 3. Instantiate PriceCache
    ├── 4. Instantiate OrderExecutor (based on --mode)
    ├── 5. Instantiate AbortHandler (wraps OrderExecutor)
    ├── 6. Instantiate SignalQueue
    ├── 7. Instantiate RouteCalculator
    ├── 8. Instantiate RiskGate (references SignalQueue, OrderExecutor)
    ├── 9. Instantiate ArbitrageEngine (references PriceCache, RouteCalculator, SignalQueue)
    ├── 10. Instantiate HeartbeatMonitor (references PriceCache, ArbitrageEngine)
    ├── 11. Instantiate ReconnectHandler
    ├── 12. Instantiate BitgetWebSocketClient (references PriceCache, ReconnectHandler)
    │
    ├── 13. Run StartupChecker.run()          ← blocks until stale orders cleaned
    ├── 14. Run NetworkChecker.preflight()    ← blocks; aborts if latency > threshold
    ├── 15. Connect BitgetWebSocketClient     ← blocks until first price received
    ├── 16. Start HeartbeatMonitor
    ├── 17. Start RiskGate consumer thread
    ├── 18. Start NetworkChecker monitor      ← 30s periodic latency checks
    └── 19. Start ArbitrageEngine             ← 100ms ticks begin
```

---

## 5. Shutdown Sequence

```
Shutdown Hook (SIGINT / SIGTERM)
    │
    ├── 1. Stop ArbitrageEngine (cancel scheduled task)
    ├── 2. Stop HeartbeatMonitor
    ├── 3. Close BitgetWebSocketClient
    ├── 4. Drain SignalQueue (discard remaining signals)
    ├── 5. Wait for in-flight execution to complete (with timeout)
    ├── 6. Shut down ExecutorService pools
    └── 7. Log shutdown complete
```

---

## 6. Error Handling Strategy

| Error Type | Handler | Action |
|---|---|---|
| WebSocket disconnect | `ReconnectHandler` | Exponential backoff reconnect |
| Stale price data (>5s) | `HeartbeatMonitor` | Kill engine immediately |
| Leg 2/3 execution failure | `AbortHandler` | Market-sell to flatten position |
| API rate limit (HTTP 429) | `LiveExecutor` | Retry with backoff (max 3 attempts) |
| API server error (5xx) | `LiveExecutor` | Retry with backoff (max 3 attempts) |
| Invalid signal (stale) | `RiskGate` | Drop signal, log warning |
| Startup stale orders | `StartupChecker` | Cancel all open orders before engine start |
| High network latency (>400ms) | `NetworkChecker` | Pause engine until latency recovers |
| Startup latency too high | `NetworkChecker` | Refuse to start, exit with error |
| Unhandled exception | `Thread.UncaughtExceptionHandler` | Log CRITICAL, attempt graceful shutdown |

---

## 7. Monitoring & Observability

| Metric | Source | Log Level |
|---|---|---|
| Price update rate | `PriceCache` | DEBUG |
| Heartbeat status | `HeartbeatMonitor` | INFO / CRITICAL |
| Signals detected per minute | `ArbitrageEngine` | INFO |
| Signals accepted / rejected | `RiskGate` | INFO |
| Trade execution result | `OrderExecutor` | INFO |
| Abort events | `AbortHandler` | CRITICAL |
| Reconnection attempts | `ReconnectHandler` | WARN |
| Network latency (avg ms) | `NetworkChecker` | INFO / CRITICAL |
| Engine paused (latency) | `NetworkChecker` | WARN |
| Queue depth | `SignalQueue` | DEBUG |
